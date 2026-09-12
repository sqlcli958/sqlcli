package com.sqlcli.graph.eval;

import com.sqlcli.graph.policy.PolicyEvaluator;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.LineageKind;
import com.sqlcli.graph.workspace.LineageRecord;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.TermWorkspaceNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图谱评估：跑一遍探针，产出 finding + 覆盖率指标。纯函数，不连库、不写图谱。
 *
 * <p><b>只放「无论如何都是错」的判据。</b>「表描述覆盖 70% 够不够」这类问题没有普适答案，
 * 拿它当 finding 就是在生产一份没人能执行的报告——`schema policy` 最后一次运行报了
 * 1487 条违规、其中 1215 条是永远不会改的遗留表名，之后无人再跑。
 *
 * <p>同一条教训还换来了 {@link #FINDING_LIMIT}：<b>当一类 finding 的数量超过人能处理的
 * 量级，它报的不是「图谱错了」而是「规则用错了对象」</b>——所以单个探针超过阈值就停止列举，
 * 改报一条说明规则用错了对象的 finding。
 */
public class GraphEvaluator {

    /** 单个探针的列举上限。超过就不再逐条列，改报一条「规则用错了对象」。 */
    public static final int FINDING_LIMIT = 50;

    /**
     * join 两端类型的默认兼容组。policy 那条规则的兼容组由规则集给（各库类型名不同），
     * 评估没有规则集，只能带一份默认——宽一点是故意的：这里的判据必须是
     * 「无论如何都是错」，把 int 连 bigint 报成硬错误只会让整份报告失去可信度。
     */
    private static final Collection<Set<String>> COMPATIBLE_TYPE_GROUPS = List.of(
            Set.of("int", "int4", "int8", "integer", "bigint", "smallint", "tinyint", "mediumint",
                    "number", "numeric", "decimal", "serial", "bigserial", "long"),
            Set.of("char", "varchar", "varchar2", "nvarchar", "nvarchar2", "nchar", "text",
                    "character varying", "character", "string", "clob"),
            Set.of("date", "datetime", "timestamp", "timestamptz", "datetime2",
                    "timestamp without time zone", "timestamp with time zone"),
            Set.of("uuid", "uniqueidentifier"));

    /**
     * 描述里可能是表引用的片段。光靠这个正则拦不住误报，真正的判据是
     * {@link #tableNamePrefixes}——token 必须长得像<b>本库里的表名</b>。
     */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*");

    /** 文件名不是表引用：`ErpPropReportMapper.xml` 这种在描述里指的是代码出处。 */
    private static final Pattern FILE_SUFFIX = Pattern.compile(
            "\\.(xml|java|sql|md|json|ya?ml|ts|tsx|js|py|txt|csv|properties)$");

    public GraphEvaluation evaluate(GraphWorkspace workspace) {
        String alias = workspace.getManifest() == null ? "<alias>" : workspace.getManifest().getAlias();
        List<GraphFinding> findings = new ArrayList<>();
        findings.addAll(gate("term.orphan", termOrphan(workspace, alias)));
        findings.addAll(gate("desc.dead-ref", deadReferences(workspace, alias)));
        findings.addAll(gate("rel.endpoint-mismatch", endpointMismatch(workspace, alias)));
        findings.addAll(gate("lineage.shape", lineageShape(workspace, alias)));
        return new GraphEvaluation(metrics(workspace), findings);
    }

    /**
     * 数量闸门：一个探针报 137 条，人不会去修，只会不再跑这个命令。
     * 超过阈值就换一条 finding——它说的不是「图谱有 137 处错」，是「这个探针用错了对象」，
     * 但仍然带上前三条样例和其中一条的修复命令，让人能自己判断是哪种情况。
     *
     * <p>public static 是因为数据层探针（{@code schema data-quality}）要走同一道闸门。
     * 那边不连图谱、跑的是另一套判据，但「一类 finding 多到人处理不了 = 规则用错了对象」
     * 这句话与探针是谁无关，复制一份的结果是两个阈值。
     */
    public static List<GraphFinding> gate(String probe, List<GraphFinding> findings) {
        if (findings.size() <= FINDING_LIMIT) return findings;
        List<String> samples = findings.stream().limit(3).map(GraphFinding::targetId).toList();
        GraphFinding first = findings.get(0);
        return List.of(new GraphFinding(probe, first.severity(), null,
                probe + " 产出 " + findings.size() + " 条，超过闸门 " + FINDING_LIMIT
                        + "：这不是图谱有这么多处错，是这个探针用错了对象。"
                        + "先拿样例验证判断，确认规则该收窄就改探针，确认图谱确实如此就分批修。"
                        + "样例：" + String.join("、", samples),
                first.remediation()));
    }

    // ---------------------------------------------------------------- 探针

    /**
     * 空壳术语：既没有描述、也没有映射、也没有入口表。
     *
     * <p>它不是「少了点东西」而是<b>主动有害</b>：检索里术语权重最高，一条没有内容的术语
     * 稳稳占住 Top1，把真表压低一名。实测 5 条空壳术语让 Top1 从 55.6% 掉到 11.1%。
     */
    private List<GraphFinding> termOrphan(GraphWorkspace workspace, String alias) {
        Set<String> mapped = new LinkedHashSet<>();
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            if (relation.getType() == RelationType.term_mapping
                    && relation.getStatus() != GraphStatus.ignored) {
                mapped.add(relation.getFrom());
            }
        }
        List<GraphFinding> findings = new ArrayList<>();
        for (TermWorkspaceNode term : workspace.getTerms().values()) {
            if (term.getStatus() == GraphStatus.ignored) continue;
            if (notBlank(term.getDescription()) || notBlank(term.getPrimaryTarget())
                    || mapped.contains(term.getId())) continue;
            findings.add(GraphFinding.error("term.orphan", term.getId(),
                    "术语「" + term.getName() + "」既没有描述也没有映射目标，"
                            + "检索会让它占住 Top1 却给不出任何落点",
                    "sql-cli " + alias + " schema add-term " + term.getName()
                            + " --description '<这个词在业务上指什么>' --map <schema.table>"));
        }
        return findings;
    }

    /**
     * 描述里提到的表在图谱里不存在。
     *
     * <p>只看 {@code description}（人和 agent 写的），不看 {@code comment}（库里带出来的）：
     * 库注释里的死引用不是图谱的问题，而且改不动——它下一次导入又回来了。
     */
    private List<GraphFinding> deadReferences(GraphWorkspace workspace, String alias) {
        Set<String> known = knownNames(workspace);
        Set<String> prefixes = tableNamePrefixes(workspace);
        List<GraphFinding> findings = new ArrayList<>();
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            for (String dead : deadRefs(table.getDescription(), known, prefixes)) {
                findings.add(GraphFinding.error("desc.dead-ref", table.getId(),
                        "表 " + table.getQualifiedName() + " 的描述提到 " + dead + "，图谱里没有这个对象",
                        "sql-cli " + alias + " schema edit --table " + table.getQualifiedName()
                                + " --description '<改掉其中的 " + dead + ">'"));
            }
            for (ColumnWorkspaceNode column : table.getColumns()) {
                for (String dead : deadRefs(column.getDescription(), known, prefixes)) {
                    String ref = table.getQualifiedName() + "." + column.getName();
                    findings.add(GraphFinding.error("desc.dead-ref",
                            column.computeId(table.getSourceAlias(), table.getSchema(), table.getName()),
                            "字段 " + ref + " 的描述提到 " + dead + "，图谱里没有这个对象",
                            "sql-cli " + alias + " schema edit --column " + ref
                                    + " --description '<改掉其中的 " + dead + ">'"));
                }
            }
        }
        return findings;
    }

    /** 关系两端字段类型对不上。判据与 policy 的 {@code join_column_type_match} 是同一份。 */
    private List<GraphFinding> endpointMismatch(GraphWorkspace workspace, String alias) {
        List<GraphFinding> findings = new ArrayList<>();
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            if (!PolicyEvaluator.isJoinRelation(relation)) continue;
            ColumnWorkspaceNode left = workspace.findColumnById(relation.getFrom());
            ColumnWorkspaceNode right = workspace.findColumnById(relation.getTo());
            if (left == null || right == null) continue;
            // suspicious（同类型不同长度）不进硬错误：varchar(32) 连 varchar(64) 能跑，
            // 它是设计建议，归 policy 管；这里只报「无论如何都是错」的类型不兼容。
            if (!"incompatible".equals(PolicyEvaluator.joinTypeMismatch(
                    left.getDataType(), right.getDataType(), COMPATIBLE_TYPE_GROUPS))) continue;
            findings.add(GraphFinding.error("rel.endpoint-mismatch", relation.getId(),
                    "关系 " + ref(relation.getFrom()) + " ↔ " + ref(relation.getTo())
                            + " 两端类型不兼容（" + typeOf(left) + " 连 " + typeOf(right)
                            + "）：按它写出的 JOIN 要么报错，要么隐式转换后走不上索引",
                    "sql-cli " + alias + " schema add-relation --type join_observed --from "
                            + ref(relation.getFrom())
                            + " --to <类型一致的那一列 schema.table.column> --verified"
                            + "   # 这条边本身是误挖的，就去评审页拒绝它"));
        }
        return findings;
    }

    /**
     * 血缘的形状对不对：源含目标、类别与字段不符、端点列已不存在。
     *
     * <p>warning 不是 error：写入闸门 {@link LineageKind#validate} 已经挡住新写的，这里查的是
     * 闸门之前写的和导入进来的。它们错着也不会让评估「失败」——一条自指血缘骗不到 SQL，
     * 只会让 describe 多一行看不懂的上游。判据与闸门是同一份，两边不会各说各话。
     */
    private List<GraphFinding> lineageShape(GraphWorkspace workspace, String alias) {
        List<GraphFinding> findings = new ArrayList<>();
        for (LineageRecord record : workspace.getLineage().values()) {
            String remove = "sql-cli " + alias + " schema remove-lineage --id " + record.getId();
            String shape = LineageKind.validate(record.getLineageKind(), record.getTarget(),
                    record.getSources(), record.getExpression());
            if (shape != null) {
                findings.add(GraphFinding.warning("lineage.shape", record.getId(),
                        "血缘 " + ref(record.getTarget()) + " 形状不对：" + shape,
                        remove + "   # 或按正确的 --kind 重跑 add-lineage 覆盖"));
                continue;
            }
            List<String> missing = new ArrayList<>();
            if (workspace.findColumnById(record.getTarget()) == null) missing.add(ref(record.getTarget()));
            for (String source : record.getSources()) {
                if (workspace.findColumnById(source) == null) missing.add(ref(source));
            }
            if (!missing.isEmpty()) {
                findings.add(GraphFinding.warning("lineage.shape", record.getId(),
                        "血缘 " + ref(record.getTarget()) + " 引用的列在图谱里不存在：" + String.join("、", missing)
                                + "（表重导后列没了，或写的时候就错了）",
                        remove));
            }
        }
        return findings;
    }

    // ---------------------------------------------------------------- 指标

    /**
     * 覆盖率，<b>只报告不判定</b>，也不合成综合评分（加权是拍脑袋）。
     * 它存在只为一件事：让 E5 能画出「这次比上次好了还是坏了」。
     *
     * <p><b>描述覆盖率只数 {@code description}，不数 {@code comment}。</b>
     * 库注释是导入时照搬进来的镜像，不是有人整理过的痕迹——把它算进去，
     * 覆盖率就变成清单 E4 点名的那种虚荣指标：{@code erp_plush_test} 上算进 comment
     * 是「表 62.7% / 列 85.9%」，只数 description 是「表 2.8%（499 张里 14 张）/
     * 列 0.4%（10426 列里 41 个）」。后一组才是「有人整理过多少」这个问题的答案。
     *
     * <p>{@code columnComment} 单独报：它有用（agent 能不能看懂这张表，库注释也算数），
     * 但和「整理进度」是两个问题，混成一个数就两个都答不了。
     */
    private Map<String, Double> metrics(GraphWorkspace workspace) {
        int tables = 0;
        int tablesDescribed = 0;
        int columns = 0;
        int columnsDescribed = 0;
        int columnsCommented = 0;
        int columnsWithValueDomain = 0;
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            tables++;
            if (notBlank(table.getDescription())) tablesDescribed++;
            for (ColumnWorkspaceNode column : table.getColumns()) {
                columns++;
                if (notBlank(column.getDescription())) columnsDescribed++;
                if (notBlank(column.getComment())) columnsCommented++;
                if (column.getValueHints() != null && column.getValueHints().hasData()) {
                    columnsWithValueDomain++;
                }
            }
        }
        Set<String> mapped = new LinkedHashSet<>();
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            if (relation.getType() == RelationType.term_mapping
                    && relation.getStatus() != GraphStatus.ignored) {
                mapped.add(relation.getFrom());
            }
        }
        int terms = 0;
        int termsMapped = 0;
        for (TermWorkspaceNode term : workspace.getTerms().values()) {
            terms++;
            if (mapped.contains(term.getId()) || notBlank(term.getPrimaryTarget())) termsMapped++;
        }
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("tableDescription", ratio(tablesDescribed, tables));
        metrics.put("columnDescription", ratio(columnsDescribed, columns));
        metrics.put("columnComment", ratio(columnsCommented, columns));
        metrics.put("columnValueDomain", ratio(columnsWithValueDomain, columns));
        metrics.put("termMapping", ratio(termsMapped, terms));
        return metrics;
    }

    /** 分母为 0 时给 1.0：一张表都没有的图谱不该报「覆盖率 0%」，那是在说一个不存在的问题。 */
    private static double ratio(int part, int total) {
        if (total == 0) return 1.0;
        return Math.round(part * 10000.0 / total) / 10000.0;
    }

    // ---------------------------------------------------------------- 工具

    /** 图谱里所有可被描述引用到的名字：表名、限定表名、schema 名、字段名。 */
    private Set<String> knownNames(GraphWorkspace workspace) {
        Set<String> names = new LinkedHashSet<>();
        workspace.getSchemas().values().forEach(schema -> names.add(lower(schema.getName())));
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            names.add(lower(table.getName()));
            names.add(lower(table.getQualifiedName()));
            names.add(lower(table.getSchema()));
            for (ColumnWorkspaceNode column : table.getColumns()) {
                names.add(lower(column.getName()));
                names.add(lower(table.getName() + "." + column.getName()));
            }
        }
        names.remove(null);
        return names;
    }

    private List<String> deadRefs(String description, Set<String> known, Set<String> prefixes) {
        if (description == null || description.isBlank() || prefixes.isEmpty()) return List.of();
        List<String> dead = new ArrayList<>();
        Matcher matcher = IDENTIFIER.matcher(description);
        while (matcher.find()) {
            String token = matcher.group();
            String lower = lower(token);
            if (known.contains(lower)) continue;
            // schema.table.column 这类三段引用：掐掉最后一段再试一次
            int lastDot = lower.lastIndexOf('.');
            if (lastDot > 0 && known.contains(lower.substring(0, lastDot))) continue;
            if (FILE_SUFFIX.matcher(lower).find()) continue;
            // 限定名取最后一段当表名：qm_pct.erp_prop_report → erp_prop_report
            String name = lastDot < 0 ? lower : lower.substring(lastDot + 1);
            if (prefixes.stream().noneMatch(name::startsWith)) continue;
            if (!dead.contains(token)) dead.add(token);
        }
        return dead;
    }

    /**
     * 本图谱里表名的命名前缀（到第一个下划线为止），只保留被 <b>≥3 张表</b>共用的。
     *
     * <p>没有这道闸门，判据就是「带下划线且图谱里查不到」——真图谱上跑出来 5 条全是误报：
     * 字典类型名 {@code report_state}、Mapper 文件名 {@code ErpPropReportMapper.xml}、
     * SQL 函数 {@code find_in_set}×3。描述里正常就会提到这三类东西。
     * 表名的共同前缀是这个库里「表长什么样」的事实，比任何写死的黑名单都准。
     *
     * <p>ponytail: 表名不带下划线的库（前缀集为空）这条探针整个静默——宁可漏报也不误报，
     * 误报一次就没人再跑这个命令了。真遇到这种库再补别的判据。
     */
    private Set<String> tableNamePrefixes(GraphWorkspace workspace) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TableWorkspaceNode table : workspace.getTables().values()) {
            String name = lower(table.getName());
            int underscore = name == null ? -1 : name.indexOf('_');
            if (underscore <= 0) continue;
            counts.merge(name.substring(0, underscore + 1), 1, Integer::sum);
        }
        Set<String> prefixes = new LinkedHashSet<>();
        counts.forEach((prefix, count) -> {
            if (count >= 3) prefixes.add(prefix);
        });
        return prefixes;
    }

    /** {@code column:alias:schema.table.col} → {@code schema.table.col}，命令里能直接粘的形式。 */
    private static String ref(String nodeId) {
        if (nodeId == null) return "<ref>";
        String[] parts = nodeId.split(":", 3);
        return parts.length < 3 ? nodeId : parts[2];
    }

    private static String typeOf(ColumnWorkspaceNode column) {
        return column.getDataType() == null ? "unknown" : String.valueOf(column.getDataType().getNormalized());
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
