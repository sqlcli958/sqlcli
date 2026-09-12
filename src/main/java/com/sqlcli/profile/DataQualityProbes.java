package com.sqlcli.profile;

import com.sqlcli.graph.eval.GraphEvaluator;
import com.sqlcli.graph.eval.GraphFinding;
import com.sqlcli.graph.policy.PolicyEvaluator;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.strategy.DatabaseStrategies;
import com.sqlcli.strategy.DatabaseStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 数据层质量探针——三层三套工具里空着的那一格。
 *
 * <h2>为什么不并进 {@code schema eval}</h2>
 * {@code schema eval} 问的是「图谱够不够、准不准」，纯函数、不连库；这里问的是
 * 「库里的值对不对」，非连库不可。判据、代价、授权要求三样都不同，合成一个命令的结果是
 * 「跑一次评估」既可能是毫秒级的纯计算、也可能是往生产库上打几百条聚合查询。
 *
 * <h2>四条探针都是重构决策的直接输入</h2>
 * 它们回答的是重构最常问的三句话，而这三句<b>代码永远查不到答案</b>——
 * 代码只能说有没有人写这行，不能说这行还跑不跑：
 * <ul>
 *   <li>{@code data.column-unused}：这个字段能删吗</li>
 *   <li>{@code data.enum-undeclared}：这个枚举值还有人写吗（反过来：有人在写图谱没声明的值）</li>
 *   <li>{@code data.orphan-fk}：这条外键还成立吗</li>
 *   <li>{@code data.table-dead}：这张表还活着吗</li>
 * </ul>
 *
 * <h2>判据一律相对，不用绝对基数</h2>
 * E1 的教训（见 dev-checklist E1 交付说明第 1 条）：直接拿 profiler 的
 * {@code lowCardinality}（distinct &lt; 50 的<b>绝对</b>阈值）当枚举判据，在一张 39 行的表上
 * <b>每一列</b>都满足，35 列报出 31 条发现、25 条是垃圾。所以这里的阈值全部是比例
 * （{@link #DOMINANT_VALUE_RATIO}、孤儿占比），它们天然随表规模缩放；唯二的绝对数是
 * {@link ValueDomainCrosscheck#MIN_SAMPLE}（样本太少不下结论）和「多少个月算死」，
 * 后者是参数不是判据。
 *
 * <h2>只有 enum-undeclared 是硬错误</h2>
 * 其余三条是 warning：「这个字段 99% 是同一个值」是**值得看**，不是**一定错**——
 * 有可能这就是个刚上线还没铺开的功能。而「库里有图谱没声明的取值」和 E1 一样是硬错误：
 * agent 按图谱写 {@code WHERE status IN (...)} 会静默漏掉这批数据，SQL 照跑、不报错。
 */
public final class DataQualityProbes {

    private static final Logger log = LoggerFactory.getLogger(DataQualityProbes.class);

    /** 「最后一次写入距今多少个月算死」的默认值，可用 {@code --months} 改。 */
    public static final int DEFAULT_DEAD_MONTHS = 12;

    /**
     * 单值集中度阈值：非空值里有这么大比例落在同一个取值上，就认为这个字段实际没在用。
     *
     * <p>来源是 dev-checklist E1「四种结论」那条——<b>单值 &gt;99% 或全 NULL = 这个字段
     * 实际没在用</b>。E1 当时只做到了 distinct == 1（100%），因为
     * {@code ColumnProfile} 不带每个值的计数；{@code topCount} 就是为补上这 1% 加的。
     */
    static final double DOMINANT_VALUE_RATIO = 0.99;

    /**
     * 「建表时间」列的名字。写死一张表而不是猜「任何 datetime 列」：一张表上
     * {@code update_time} / {@code finish_time} / {@code plan_date} 都是时间列，
     * 但只有创建时间能回答「还有没有新数据进来」，拿 {@code plan_date} 判表死会反过来。
     */
    private static final Pattern CREATE_TIME_COLUMN = Pattern.compile(
            "^(gmt_create|(create|created|add|insert)_?(time|date|at))$", Pattern.CASE_INSENSITIVE);

    private DataQualityProbes() {
    }

    /**
     * 跑完四条探针。连接由调用方管生命周期（与 {@link TableProfiler} 同一约定）。
     *
     * @param deadMonths 最后一次写入早于这么多个月就报「表可能已死」
     */
    public static List<GraphFinding> run(Connection connection, String dbType, GraphWorkspace workspace,
            TableWorkspaceNode table, int deadMonths, ProfileOptions options) {
        String alias = workspace.getManifest().getAlias();
        DatabaseStrategy strategy = DatabaseStrategies.resolve(dbType);
        List<ColumnSpec> specs = new ArrayList<>();
        for (ColumnWorkspaceNode column : table.getColumns()) {
            specs.add(new ColumnSpec(column.getName(), column.getSemanticType()));
        }
        TableProfile profile = TableProfiler.profile(connection, dbType, table.getSchema(), table.getName(),
                specs, options);

        List<GraphFinding> findings = new ArrayList<>();
        findings.addAll(GraphEvaluator.gate("data.column-unused", columnUnused(profile, table, alias)));
        findings.addAll(GraphEvaluator.gate("data.enum-undeclared", enumUndeclared(profile, table, alias)));
        findings.addAll(GraphEvaluator.gate("data.orphan-fk",
                orphanForeignKeys(connection, table, alias,
                        orphanQueries(strategy, dbType, workspace, table, options),
                        options.queryTimeoutSeconds())));
        findings.addAll(tableDead(connection, strategy, profile, table, alias, deadMonths,
                options.queryTimeoutSeconds()));
        return findings;
    }

    /**
     * 连库之前打给用户看的那份清单——授权的对象是**这些查询**，不是一个命令名。
     *
     * <p>SQL 由跑它们的同一批方法拼出来，不另抄一份：抄一份的结果是打印的和执行的
     * 慢慢对不上，而那时用户批准的就是一份假清单。逐列的两条查询各表一遍就够，
     * 后面注明字段数——把 31 个字段 × 2 条全打出来，人只会直接翻到底按回车。
     */
    public static List<String> plannedQueries(String dbType, GraphWorkspace workspace,
            TableWorkspaceNode table, ProfileOptions options) {
        DatabaseStrategy strategy = DatabaseStrategies.resolve(dbType);
        List<String> queries = new ArrayList<>();
        queries.add("SELECT COUNT(*) FROM " + strategy.qualifyTableName(table.getSchema(), table.getName()));
        if (!table.getColumns().isEmpty()) {
            String quotedCol = strategy.quoteIdentifier(table.getColumns().get(0).getName());
            String sampledFrom = TableProfiler.sampledFrom(strategy, dbType, table.getSchema(), table.getName(),
                    quotedCol, options.sampleSize());
            String suffix = "   -- 每个字段一条，共 " + table.getColumns().size() + " 个字段";
            queries.add(TableProfiler.aggregateSql(quotedCol, sampledFrom) + suffix);
            queries.add(TableProfiler.topValueSql(dbType, quotedCol, sampledFrom) + suffix);
        }
        ColumnWorkspaceNode timeColumn = createTimeColumn(table);
        if (timeColumn != null) {
            queries.add(maxValueSql(strategy, table, timeColumn));
        }
        for (OrphanQuery orphan : orphanQueries(strategy, dbType, workspace, table, options)) {
            queries.add(orphan.totalSql());
            queries.add(orphan.orphanSql());
        }
        return queries;
    }

    // ------------------------------------------------------------- data.column-unused

    /**
     * 整列全 NULL，或非空值 ≥99% 压在同一个取值上 → 这个字段实际没在用。
     *
     * <p>不自动改图谱，只提议标注：机器看到的是「这个环境的这次采样里没在用」，
     * 而「废弃字段」和「测试库没造这批数据」是两个结论，差别只有人知道。
     */
    static List<GraphFinding> columnUnused(TableProfile profile, TableWorkspaceNode table, String alias) {
        List<GraphFinding> findings = new ArrayList<>();
        for (ColumnProfile column : profile.columns()) {
            if (column.skipped() || column.rowsSampled() < ValueDomainCrosscheck.MIN_SAMPLE) continue;
            String detail;
            if (column.nonNullCount() == 0) {
                detail = "整列全为 NULL（采样 " + column.rowsSampled() + " 行）";
            } else if (column.topCount() >= column.nonNullCount() * DOMINANT_VALUE_RATIO) {
                long percent = Math.round(column.topCount() * 100.0 / column.nonNullCount());
                // 敏感列的 topValue 恒为 null（ColumnSpec.sensitive），此时只报占比
                detail = percent + "% 的非空值是同一个取值"
                        + (column.topValue() == null ? "" : "（" + column.topValue() + "）");
            } else {
                continue;
            }
            String ref = table.getSchema() + "." + table.getName() + "." + column.columnName();
            findings.add(GraphFinding.warning("data.column-unused",
                    GraphIds.columnId(alias, table.getSchema(), table.getName(), column.columnName()),
                    "字段 " + ref + " " + detail + "，实际没在用——agent 不该拿它做判断，重构时它是删除候选",
                    "先确认是废弃字段还是这个环境没造数据，再标注（不自动写）：sql-cli " + alias
                            + " schema edit --column " + ref + " --description '未启用：<废弃 / 本环境无数据>'"));
        }
        return findings;
    }

    // ---------------------------------------------------------- data.enum-undeclared

    /**
     * 库里出现图谱 {@code enumValues} 之外的取值。
     *
     * <p>判据整个借 {@link ValueDomainCrosscheck#cross}，一行都不重写——这条探针做的只是
     * 把它已经算得出来的那个结论也落进 finding 表。注释和代码枚举都不传：这条问的就是
     * 「图谱里写的够不够」，注释里补过的值救不了按图谱写 SQL 的 agent。
     *
     * <p>{@code asserted=true}：图谱里写着值域，就是已经有人回答过「它是不是枚举」，
     * 那组防噪音的启发式不该再拦。
     *
     * <p><b>已知盲区</b>：{@code enumValues} 只在低基数列上采得到（{@code lowCardinality}），
     * 所以「图谱声明了 3 个值、库里实际有 300 个」这种落差这条探针看不见，它会静默跳过。
     */
    static List<GraphFinding> enumUndeclared(TableProfile profile, TableWorkspaceNode table, String alias) {
        List<GraphFinding> findings = new ArrayList<>();
        for (ColumnProfile columnProfile : profile.columns()) {
            ColumnWorkspaceNode column = table.findColumn(columnProfile.columnName());
            if (column == null || column.getValueHints() == null) continue;
            List<String> declared = column.getValueHints().getEnumValues();
            if (declared == null || declared.isEmpty()) continue;

            ValueDomainVerdict verdict = ValueDomainCrosscheck.cross(columnProfile, null, null, declared, true);
            if (verdict.skipped() || verdict.onlyInDatabase().isEmpty()) continue;

            String ref = table.getSchema() + "." + table.getName() + "." + column.getName();
            findings.add(GraphFinding.error("data.enum-undeclared",
                    GraphIds.columnId(alias, table.getSchema(), table.getName(), column.getName()),
                    "字段 " + ref + " 库里存在图谱未声明的取值 " + verdict.onlyInDatabase()
                            + "（图谱声明的是 " + declared + "）：agent 按图谱写 WHERE ... IN 会静默漏掉这批数据",
                    "sql-cli " + alias + " schema value-domain --table " + table.getSchema() + "."
                            + table.getName() + " --column " + column.getName()
                            + " --code-enum '<把这几个取值的含义补上>' --basis '<从哪个类 / 字典表读到的>'"));
        }
        return findings;
    }

    // -------------------------------------------------------------- data.orphan-fk

    /** 一条已验证关系边要跑的两条计数查询。SQL 只在这里拼一次，预览和执行共用。 */
    record OrphanQuery(String relationId, String fromRef, String toRef, String totalSql, String orphanSql) {
    }

    /**
     * 只查图谱里**已验证**的关系边，不猜。
     *
     * <p>候选边（{@code verified != true}）本来就还没被人确认存在，拿它去查孤儿
     * 得到的是「这条边是不是编错了」——那是关系发现的问题，归评审队列管，不是数据质量。
     */
    static List<OrphanQuery> orphanQueries(DatabaseStrategy strategy, String dbType, GraphWorkspace workspace,
            TableWorkspaceNode table, ProfileOptions options) {
        List<OrphanQuery> queries = new ArrayList<>();
        for (RelationWorkspaceEdge relation : workspace.getRelations()) {
            if (!PolicyEvaluator.isJoinRelation(relation)) continue;
            if (!Boolean.TRUE.equals(relation.getVerified())) continue;
            TableWorkspaceNode fromTable = tableOf(workspace, relation.getFrom());
            TableWorkspaceNode toTable = tableOf(workspace, relation.getTo());
            if (fromTable == null || toTable == null) continue;
            // 只跑「这张表指出去」的边：reverse 方向的孤儿是对面那张表的问题，
            // 在这里报会让人对着一张自己没在看的表做决定
            if (!fromTable.getId().equals(table.getId())) continue;

            String fromCol = strategy.quoteIdentifier(columnLeaf(relation.getFrom()));
            String toCol = strategy.quoteIdentifier(columnLeaf(relation.getTo()));
            String sampledFrom = TableProfiler.sampledFrom(strategy, dbType, fromTable.getSchema(),
                    fromTable.getName(), fromCol, options.sampleSize());
            String total = "SELECT COUNT(*) FROM " + sampledFrom + " WHERE " + fromCol + " IS NOT NULL";
            // NOT IN 的子查询里显式排掉 NULL：留着的话整个 NOT IN 恒为 UNKNOWN，
            // 孤儿数永远是 0——这条探针会安静地永远不报任何东西
            String orphan = total + " AND " + fromCol + " NOT IN (SELECT " + toCol + " FROM "
                    + strategy.qualifyTableName(toTable.getSchema(), toTable.getName())
                    + " WHERE " + toCol + " IS NOT NULL)";
            queries.add(new OrphanQuery(relation.getId(),
                    fromTable.getQualifiedName() + "." + columnLeaf(relation.getFrom()),
                    toTable.getQualifiedName() + "." + columnLeaf(relation.getTo()), total, orphan));
        }
        return queries;
    }

    static List<GraphFinding> orphanForeignKeys(Connection connection, TableWorkspaceNode table, String alias,
            List<OrphanQuery> queries, int timeoutSeconds) {
        List<GraphFinding> findings = new ArrayList<>();
        for (OrphanQuery query : queries) {
            long sampled = count(connection, query.totalSql(), timeoutSeconds);
            if (sampled <= 0) continue;
            long orphans = count(connection, query.orphanSql(), timeoutSeconds);
            if (orphans <= 0) continue;
            double ratio = (double) orphans / sampled;
            findings.add(GraphFinding.warning("data.orphan-fk", query.relationId(),
                    "关系 " + query.fromRef() + " → " + query.toRef() + " 有 " + orphans + "/" + sampled
                            + " 个非空值（" + percent(ratio) + "）在对端找不到："
                            + "按这条边写 INNER JOIN 会静默丢掉这批行",
                    "先查清这批孤儿是历史脏数据还是这条边本身错了：sql-cli " + alias + " \"SELECT * FROM "
                            + table.getQualifiedName() + " WHERE " + columnLeaf(query.fromRef())
                            + " NOT IN (SELECT " + columnLeaf(query.toRef()) + " FROM "
                            + tableRef(query.toRef()) + ") LIMIT 20\""));
        }
        return findings;
    }

    // ------------------------------------------------------------- data.table-dead

    /**
     * 行数为 0，或建表时间列的最大值早于 N 个月 → 这张表可能已死。
     *
     * <p>最大值走一条独立的 {@code SELECT MAX(...)}，不复用 {@link ColumnProfile#maxValue()}：
     * 那个是**采样内**的最大值，而采样是不带 ORDER BY 的 LIMIT，多数库返回的是最先插入的
     * 那一批行——拿它判「最后一次写入」正好会把一张天天在写的大表判成死表。
     */
    static List<GraphFinding> tableDead(Connection connection, DatabaseStrategy strategy, TableProfile profile,
            TableWorkspaceNode table, String alias, int deadMonths, int timeoutSeconds) {
        String ref = table.getQualifiedName();
        String remediation = "确认没有别的写入方之后再决定下线，先标注：sql-cli " + alias
                + " schema edit --table " + ref + " --description '疑似停用：<最后写入时间 / 被谁取代>'";
        if (profile.rowCountAvailable() && profile.rowCount() == 0) {
            return List.of(GraphFinding.warning("data.table-dead", table.getId(),
                    "表 " + ref + " 行数为 0，可能已经不在用——重构时它是删除候选", remediation));
        }
        ColumnWorkspaceNode timeColumn = createTimeColumn(table);
        if (timeColumn == null) return List.of();
        LocalDateTime latest = maxTimestamp(connection, strategy, table, timeColumn, timeoutSeconds);
        if (latest == null) return List.of();
        LocalDateTime cutoff = LocalDateTime.now().minusMonths(deadMonths);
        if (!latest.isBefore(cutoff)) return List.of();
        return List.of(GraphFinding.warning("data.table-dead", table.getId(),
                "表 " + ref + " 的 " + timeColumn.getName() + " 最大值是 " + latest.toLocalDate()
                        + "，超过 " + deadMonths + " 个月没有新数据写入——这张表可能已经不在用",
                remediation));
    }

    static ColumnWorkspaceNode createTimeColumn(TableWorkspaceNode table) {
        for (ColumnWorkspaceNode column : table.getColumns()) {
            if (column.getName() != null && CREATE_TIME_COLUMN.matcher(column.getName()).matches()) {
                return column;
            }
        }
        return null;
    }

    private static String maxValueSql(DatabaseStrategy strategy, TableWorkspaceNode table,
            ColumnWorkspaceNode column) {
        return "SELECT MAX(" + strategy.quoteIdentifier(column.getName()) + ") FROM "
                + strategy.qualifyTableName(table.getSchema(), table.getName());
    }

    /**
     * 先按 {@code getTimestamp} 取（驱动自己做转换，Oracle 的 DATE 只有这条路对），
     * 取不到再退回字符串的前 10 位当 ISO 日期解析（MySQL / PostgreSQL / SQLite 的
     * {@code getString} 都是 {@code yyyy-MM-dd ...}）。两条都不成就放弃这条探针——
     * 猜错一个日期格式的代价是把一张活表报成死表。
     */
    private static LocalDateTime maxTimestamp(Connection connection, DatabaseStrategy strategy,
            TableWorkspaceNode table, ColumnWorkspaceNode column, int timeoutSeconds) {
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = st.executeQuery(maxValueSql(strategy, table, column))) {
                if (!rs.next()) return null;
                try {
                    Timestamp timestamp = rs.getTimestamp(1);
                    if (timestamp != null) return timestamp.toLocalDateTime();
                } catch (Exception ignored) {
                    // 驱动转不了就走下面的字符串解析
                }
                String text = rs.getString(1);
                if (text == null || text.length() < 10) return null;
                return LocalDate.parse(text.substring(0, 10)).atStartOfDay();
            }
        } catch (Exception e) {
            TableProfiler.rollbackIfNeeded(connection);
            log.debug("最大写入时间查询失败 {}.{}: {}", table.getQualifiedName(), column.getName(), e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------- 工具

    /** 查不出来返回 -1，调用方按「这条探针在这条边上没结论」处理，不当成 0 个孤儿。 */
    private static long count(Connection connection, String sql, int timeoutSeconds) {
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            TableProfiler.rollbackIfNeeded(connection);
            log.debug("孤儿外键计数失败: {} / {}", sql, e.getMessage());
            return -1;
        }
    }

    private static TableWorkspaceNode tableOf(GraphWorkspace workspace, String columnId) {
        if (columnId == null) return null;
        String[] parts = columnId.split(":", 3);
        if (parts.length < 3) return null;
        int lastDot = parts[2].lastIndexOf('.');
        return lastDot <= 0 ? null : workspace.getTableByQualifiedName(parts[2].substring(0, lastDot));
    }

    /** `column:alias:schema.table.col` / `schema.table.col` 都取最后一段。 */
    private static String columnLeaf(String ref) {
        int lastDot = ref == null ? -1 : ref.lastIndexOf('.');
        return lastDot < 0 ? ref : ref.substring(lastDot + 1);
    }

    /** `schema.table.column` → `schema.table`。 */
    private static String tableRef(String columnRef) {
        int lastDot = columnRef.lastIndexOf('.');
        return lastDot < 0 ? columnRef : columnRef.substring(0, lastDot);
    }

    private static String percent(double ratio) {
        return Math.round(ratio * 1000) / 10.0 + "%";
    }
}
