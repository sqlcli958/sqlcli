package com.sqlcli.metric;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.MetricRecord;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.strategy.DatabaseStrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把一条 {@link MetricRecord} 按请求的粒度和维度展开成可执行 SQL 骨架。这是 metric 价值
 * 兑现的唯一时刻（见 dev-checklist「BI 语义层」一节）——只有模型和存储，没有这一步，
 * metric 就是又一份写完没人用的语义资料。
 *
 * <h2>两条不解析的边界，以及为什么</h2>
 * <ul>
 *   <li><b>{@code expression} / {@code filters} 不解析</b>——它们是人写的聚合口径
 *       （{@code SUM(order.amount)}、{@code status IN (2,3)}），本层原样拼进 SELECT / WHERE。
 *       解析 SQL 表达式（聚合函数、列引用、子查询）是个无底洞，且没有必要：这一层要做的是
 *       "把口径接到正确的 FROM/JOIN 上"，不是"看懂口径本身"。</li>
 *   <li><b>{@code joinExpression} 不重新拼</b>——每个 {@link MetricRecord.MetricJoinStep}
 *       引用一条已存在的 {@link RelationWorkspaceEdge}，直接使用它的
 *       {@link RelationWorkspaceEdge#getJoinExpression()}。复合外键分组之后，同一个约束的
 *       所有列边共享同一条拼好的完整 {@code AND} 条件（任取组里哪一条效果一样），如果这里
 *       改成自己按 from/to 两个端点重新拼一个等式，会漏掉复合键里除取到的那一条边之外的
 *       其余列，产出跨租户笛卡尔积且语法上完全合法、不报错——这正是复合外键分组那条改动
 *       想堵住的洞，在消费端把它挖开就白做了。</li>
 * </ul>
 *
 * <h2>FROM 起点表怎么定</h2>
 * {@link MetricRecord} 没有单独的"主表"字段——起点由结构化字段按优先级推出：
 * 先看 {@code grain.timeColumn} 所在表，没有就看第一个 {@code dimensions} 所在表，
 * 都没有就看 {@code joinPath} 第一步的起点表。三者都没有（只有 expression 的纯聚合 metric）
 * 时无法确定 FROM 哪张表，直接报错——这是模型本身的边界，不是这里能绕过的。
 */
public final class MetricSqlExpander {

    /** 比率展开输出列名，固定不带方言前缀——手算复核时就是找这两列名对着算。 */
    private static final String NUMERATOR_ALIAS = "numerator_value";
    private static final String DENOMINATOR_ALIAS = "denominator_value";

    private MetricSqlExpander() {
    }

    public static String expand(GraphWorkspace workspace, MetricRecord metric, DatabaseStrategy dialect,
            MetricSqlRequest request) {
        boolean ratio = metric.isRatio();
        if (!ratio && (metric.getExpression() == null || metric.getExpression().isBlank())) {
            throw new MetricExpansionException("metric 未声明 expression，无法生成 SELECT: " + metric.getId());
        }
        if (ratio && (isBlank(metric.getNumerator().getExpression()) || isBlank(metric.getDenominator().getExpression()))) {
            throw new MetricExpansionException(
                    "metric 是比率结构（numerator/denominator），但分子或分母缺少 expression，无法生成 SELECT: "
                            + metric.getId());
        }
        validateAdditivity(metric, request);

        String baseTableId = resolveBaseTableId(workspace, metric);
        TableWorkspaceNode baseTable = requireTable(workspace, baseTableId, "metric 的起始表");

        Map<String, TableWorkspaceNode> included = new LinkedHashMap<>();
        included.put(baseTableId, baseTable);
        List<JoinClause> joins = buildJoins(workspace, metric, included);

        String timeColumnRef = null;
        String grainSelectExpr = null;
        String grainAlias = null;
        MetricRecord.MetricGrain grain = metric.getGrain();
        if (grain != null && grain.getTimeColumn() != null && !grain.getTimeColumn().isBlank()) {
            ColumnRef timeRef = requireIncludedColumn(workspace, included, grain.getTimeColumn(), "grain.timeColumn");
            timeColumnRef = qualifiedColumnRef(dialect, timeRef);
            if (request.grain() != null && !request.grain().isBlank()) {
                List<String> declaredGrains = grain.getGrains() == null ? List.of() : grain.getGrains();
                boolean declared = declaredGrains.stream().anyMatch(g -> g.equalsIgnoreCase(request.grain()));
                if (!declared) {
                    throw new MetricExpansionException("请求的粒度 '" + request.grain()
                            + "' 不在 metric 声明的粒度范围内: " + declaredGrains);
                }
                grainSelectExpr = GrainSqlDialect.truncate(dialect.type(), timeColumnRef, request.grain());
                grainAlias = dialect.quoteIdentifier("grain_" + request.grain().toLowerCase(Locale.ROOT));
            }
        } else if (request.grain() != null && !request.grain().isBlank()) {
            throw new MetricExpansionException(
                    "metric 未声明 grain.timeColumn，无法按粒度 '" + request.grain() + "' 展开");
        }

        List<ColumnRef> dimensionRefs = new ArrayList<>();
        for (String dimensionColumnId : request.dimensionColumnIds()) {
            if (!metric.getDimensions().contains(dimensionColumnId)) {
                throw new MetricExpansionException("维度不在 metric 声明范围内: " + dimensionColumnId
                        + "，可用维度: " + metric.getDimensions());
            }
            dimensionRefs.add(requireIncludedColumn(workspace, included, dimensionColumnId, "dimensions"));
        }

        return ratio
                ? renderRatioSql(dialect, metric, baseTable, joins, grainSelectExpr, grainAlias, timeColumnRef,
                        dimensionRefs, request)
                : renderSql(dialect, metric, baseTable, joins, grainSelectExpr, grainAlias, timeColumnRef,
                        dimensionRefs, request);
    }

    // ---------------------------------------------------------------- 可加性校验（F4a）

    /**
     * F4a 的兑现点：可加性字段不是白填的标注，展开器真的会拦。
     *
     * <p>{@code semi_additive}——跨时间不可加（库存余额这类快照）——请求了 {@code --grain} 就
     * 拦下来：这里的展开是把 {@code expression} 原样按 GROUP BY 分桶求值，对一个只在某个
     * 时间点上有意义的快照做逐桶 SUM/AVG，得到的是一串没有业务含义的数，且不会报任何错。
     *
     * <p>{@code non_additive} 且不是比率结构——分子分母没有拆开声明——同样拦下 {@code --grain}
     * 或 {@code --dimensions}：非可加量唯一安全的重新聚合方式是从分子分母各自重算
     * （见 {@link #renderRatioSql}），一个裸的 {@code expression} 展开器没法验证它按桶分组后
     * 还成不成立，所以不去猜，直接报错并指向比率结构这条出路。
     *
     * <p>比率结构（{@link MetricRecord#isRatio()}）不受这条限制——它的 SQL 无论请求什么
     * 粒度/维度都是从分子分母各自重新 GROUP BY 算出来的，天然安全。
     */
    private static void validateAdditivity(MetricRecord metric, MetricSqlRequest request) {
        MetricRecord.Additivity additivity = metric.effectiveAdditivity();
        if (additivity == null) {
            return;
        }
        boolean grainRequested = request.grain() != null && !request.grain().isBlank();
        boolean grouped = grainRequested || !request.dimensionColumnIds().isEmpty();
        if (additivity == MetricRecord.Additivity.semi_additive && grainRequested) {
            throw new MetricExpansionException("metric '" + metric.getId()
                    + "' 标注为 semi_additive（跨时间不可加，例如库存余额），不能按 --grain 时间分桶展开——"
                    + "逐桶 SUM/AVG 会把只在某一时刻有意义的快照值错误相加，且不会报错。"
                    + "去掉 --grain 按总量/维度查询（跨维度可加），或改造成时点快照口径。");
        }
        if (additivity == MetricRecord.Additivity.non_additive && !metric.isRatio() && grouped) {
            throw new MetricExpansionException("metric '" + metric.getId()
                    + "' 标注为 non_additive 但不是分子/分母结构，无法安全按 --grain/--dimensions 分组展开——"
                    + "非可加量只有拆成分子/分母才能保证每组都是重新计算，而不是把已经算好的值再汇总。"
                    + "去掉 --grain/--dimensions 只查总量，或改用 numerator/denominator 声明成比率指标。");
        }
    }

    // ---------------------------------------------------------------- FROM / JOIN

    private static String resolveBaseTableId(GraphWorkspace workspace, MetricRecord metric) {
        MetricRecord.MetricGrain grain = metric.getGrain();
        if (grain != null && grain.getTimeColumn() != null && !grain.getTimeColumn().isBlank()) {
            return tableIdForColumn(grain.getTimeColumn());
        }
        if (!metric.getDimensions().isEmpty()) {
            return tableIdForColumn(metric.getDimensions().get(0));
        }
        if (!metric.getJoinPath().isEmpty()) {
            MetricRecord.MetricJoinStep firstStep = metric.getJoinPath().get(0);
            RelationWorkspaceEdge edge = findRelation(workspace, firstStep.getRelationId());
            if (edge == null) {
                throw new MetricExpansionException(
                        "metric joinPath 第 1 步引用的关系不存在: " + firstStep.getRelationId());
            }
            return tableIdForColumn(edge.getFrom());
        }
        throw new MetricExpansionException(
                "无法确定 metric 的起始表：既没有声明 grain.timeColumn，也没有 dimensions，也没有 joinPath，"
                        + "SQL 展开无从知道 FROM 哪张表: " + metric.getId());
    }

    /**
     * 按 joinPath 声明顺序把关联表接上。每一步必须恰好有一端已经在已加入的表集合里、
     * 另一端还没有——这样才知道"新增的是哪张表"；两端都在或都不在都是声明顺序有问题，
     * 直接报错而不是猜一个方向（猜错方向产出的 JOIN 链接错了表，比报错更危险）。
     */
    private static List<JoinClause> buildJoins(GraphWorkspace workspace, MetricRecord metric,
            Map<String, TableWorkspaceNode> included) {
        List<JoinClause> joins = new ArrayList<>();
        int stepIndex = 0;
        for (MetricRecord.MetricJoinStep step : metric.getJoinPath()) {
            stepIndex++;
            RelationWorkspaceEdge edge = findRelation(workspace, step.getRelationId());
            if (edge == null) {
                throw new MetricExpansionException(
                        "metric joinPath 第 " + stepIndex + " 步引用的关系不存在: " + step.getRelationId());
            }
            if (edge.getJoinExpression() == null || edge.getJoinExpression().isBlank()) {
                throw new MetricExpansionException(
                        "关系 " + edge.getId() + " 没有 joinExpression，无法生成可执行的 JOIN 条件"
                                + "（先用 schema add-relation --join 或导入补上再展开）");
            }
            String fromTableId = tableIdForColumn(edge.getFrom());
            String toTableId = tableIdForColumn(edge.getTo());
            boolean fromIncluded = included.containsKey(fromTableId);
            boolean toIncluded = included.containsKey(toTableId);
            String newTableId;
            if (fromIncluded && !toIncluded) {
                newTableId = toTableId;
            } else if (toIncluded && !fromIncluded) {
                newTableId = fromTableId;
            } else if (fromIncluded) {
                throw new MetricExpansionException("metric joinPath 第 " + stepIndex + " 步（relationId="
                        + step.getRelationId() + "）两端表都已经在 FROM 中，无法确定要新增哪张表，请检查 joinPath 顺序");
            } else {
                throw new MetricExpansionException("metric joinPath 第 " + stepIndex + " 步（relationId="
                        + step.getRelationId() + "）两端表都不在已加入的表集合中；joinPath 必须从起始表开始逐步连通，"
                        + "请检查步骤顺序或补上中间步骤");
            }
            TableWorkspaceNode newTable = requireTable(workspace, newTableId, "joinPath 第 " + stepIndex + " 步引用的表");
            joins.add(new JoinClause(step.getJoinType(), newTable, edge.getJoinExpression()));
            included.put(newTableId, newTable);
        }
        return joins;
    }

    // ---------------------------------------------------------------- 渲染

    private static String renderSql(DatabaseStrategy dialect, MetricRecord metric, TableWorkspaceNode baseTable,
            List<JoinClause> joins, String grainSelectExpr, String grainAlias, String timeColumnRef,
            List<ColumnRef> dimensionRefs, MetricSqlRequest request) {
        List<String> selectItems = new ArrayList<>();
        List<String> groupByItems = new ArrayList<>();
        if (grainSelectExpr != null) {
            selectItems.add(grainSelectExpr + " AS " + grainAlias);
            groupByItems.add(grainSelectExpr);
        }
        for (ColumnRef ref : dimensionRefs) {
            String colRef = qualifiedColumnRef(dialect, ref);
            selectItems.add(colRef);
            groupByItems.add(colRef);
        }
        // expression 不透明：原样拼，见类头注释。
        selectItems.add(metric.getExpression() + " AS " + dialect.quoteIdentifier(metric.getName()));

        StringBuilder sql = new StringBuilder("SELECT\n  ");
        sql.append(String.join(",\n  ", selectItems)).append("\n");
        sql.append("FROM ").append(dialect.qualifyTableName(baseTable.getSchema(), baseTable.getName())).append("\n");
        for (JoinClause join : joins) {
            String keyword = join.joinType() == MetricRecord.MetricJoinType.left ? "LEFT JOIN" : "INNER JOIN";
            sql.append(keyword).append(" ")
                    .append(dialect.qualifyTableName(join.table().getSchema(), join.table().getName()))
                    .append(" ON ").append(join.onExpression()).append("\n");
        }

        List<String> whereClauses = new ArrayList<>();
        if (metric.getFilters() != null && !metric.getFilters().isBlank()) {
            // filters 同样不透明，原样拼，见类头注释。
            whereClauses.add("(" + metric.getFilters() + ")");
        }
        if (timeColumnRef != null && request.timeFrom() != null && !request.timeFrom().isBlank()) {
            whereClauses.add(timeColumnRef + " >= '" + escapeLiteral(request.timeFrom()) + "'");
        }
        if (timeColumnRef != null && request.timeTo() != null && !request.timeTo().isBlank()) {
            whereClauses.add(timeColumnRef + " < '" + escapeLiteral(request.timeTo()) + "'");
        }
        if (!whereClauses.isEmpty()) {
            sql.append("WHERE ").append(String.join("\n  AND ", whereClauses)).append("\n");
        }

        if (!groupByItems.isEmpty()) {
            sql.append("GROUP BY ").append(String.join(", ", groupByItems)).append("\n");
        }

        return sql.toString().stripTrailing();
    }

    /**
     * F4b：比率指标展开成「分子子查询 JOIN 分母子查询」而不是一条 WHERE 里塞两个过滤条件——
     * 分子分母的 {@code filters} 天差地别（复购率分子要"下单≥2次"，分母不要），糅进同一个
     * WHERE 只会得到两者的交集，两个数都算错。各自独立 GROUP BY 到同一个粒度/维度键，
     * 结果按键 JOIN 回来，是唯一能让两边过滤条件互不干扰、同时保持同一分组口径的写法。
     *
     * <p>以分母为驱动表（LEFT JOIN 分子）：复购率这类典型比率里，分母是全量口径
     * （全部用户），分子是分母的子集（下单≥2次的用户）——某个分组下分子可能一行都没有
     * （没人复购），但分母那一行必须在，否则那个分组的比率直接从结果里消失、看着像
     * "没这个分组"而不是"比率是 0"。
     *
     * <p>ponytail: 假设分子的分组集合是分母分组集合的子集（复购率、转化率这类"部分/整体"
     * 型比率都满足）；如果分子能出现分母没有的分组，这里会静默丢弃那部分——
     * 真遇到这种口径再改成 FULL OUTER JOIN（MySQL/SQLite 对它的支持要单独处理）。
     */
    private static String renderRatioSql(DatabaseStrategy dialect, MetricRecord metric, TableWorkspaceNode baseTable,
            List<JoinClause> joins, String grainSelectExpr, String grainAlias, String timeColumnRef,
            List<ColumnRef> dimensionRefs, MetricSqlRequest request) {
        List<GroupKey> groupKeys = new ArrayList<>();
        if (grainSelectExpr != null) {
            groupKeys.add(new GroupKey(grainSelectExpr, grainAlias));
        }
        int dimIndex = 0;
        for (ColumnRef ref : dimensionRefs) {
            groupKeys.add(new GroupKey(qualifiedColumnRef(dialect, ref), dialect.quoteIdentifier("dim_" + dimIndex++)));
        }

        String numeratorSql = renderComponentSubquery(dialect, baseTable, joins, groupKeys,
                metric.getNumerator(), timeColumnRef, request, "numerator_value");
        String denominatorSql = renderComponentSubquery(dialect, baseTable, joins, groupKeys,
                metric.getDenominator(), timeColumnRef, request, "denominator_value");

        List<String> outerSelect = new ArrayList<>();
        for (GroupKey key : groupKeys) {
            outerSelect.add("d." + key.alias());
        }
        String numeratorValueRef = "COALESCE(n." + NUMERATOR_ALIAS + ", 0)";
        outerSelect.add(numeratorValueRef + " AS " + NUMERATOR_ALIAS);
        outerSelect.add("d." + DENOMINATOR_ALIAS + " AS " + DENOMINATOR_ALIAS);
        outerSelect.add(numeratorValueRef + " / NULLIF(d." + DENOMINATOR_ALIAS + ", 0) AS "
                + dialect.quoteIdentifier(metric.getName()));

        StringBuilder sql = new StringBuilder("SELECT\n  ");
        sql.append(String.join(",\n  ", outerSelect)).append("\n");
        sql.append("FROM (\n").append(indent(denominatorSql)).append("\n) d\n");
        if (groupKeys.isEmpty()) {
            sql.append("CROSS JOIN (\n").append(indent(numeratorSql)).append("\n) n");
        } else {
            List<String> onParts = new ArrayList<>();
            for (GroupKey key : groupKeys) {
                onParts.add("d." + key.alias() + " = n." + key.alias());
            }
            sql.append("LEFT JOIN (\n").append(indent(numeratorSql)).append("\n) n ON ")
                    .append(String.join(" AND ", onParts));
        }
        return sql.toString().stripTrailing();
    }

    /** 分子或分母一侧的独立聚合子查询：自带 FROM/JOIN，自带只属于这一侧的 filters
     * （加上请求级别的时间窗），按同一套 grain/dimensions 分组键分组。 */
    private static String renderComponentSubquery(DatabaseStrategy dialect, TableWorkspaceNode baseTable,
            List<JoinClause> joins, List<GroupKey> groupKeys, MetricRecord.MetricComponent component,
            String timeColumnRef, MetricSqlRequest request, String valueAlias) {
        List<String> selectItems = new ArrayList<>();
        List<String> groupByItems = new ArrayList<>();
        for (GroupKey key : groupKeys) {
            selectItems.add(key.expr() + " AS " + key.alias());
            groupByItems.add(key.expr());
        }
        // 分子/分母的 expression 同样不透明，原样拼，见类头注释。valueAlias 不经 quoteIdentifier：
        // 它要在外层查询里被原样引用（d.numerator_value），定义和引用必须用完全一致的写法，
        // 加引号在部分方言下会改变大小写折叠规则（如 Oracle），定义和引用两处就对不上了。
        selectItems.add(component.getExpression() + " AS " + valueAlias);

        StringBuilder sql = new StringBuilder("SELECT\n    ");
        sql.append(String.join(",\n    ", selectItems)).append("\n");
        sql.append("  FROM ").append(dialect.qualifyTableName(baseTable.getSchema(), baseTable.getName())).append("\n");
        for (JoinClause join : joins) {
            String keyword = join.joinType() == MetricRecord.MetricJoinType.left ? "LEFT JOIN" : "INNER JOIN";
            sql.append("  ").append(keyword).append(" ")
                    .append(dialect.qualifyTableName(join.table().getSchema(), join.table().getName()))
                    .append(" ON ").append(join.onExpression()).append("\n");
        }

        List<String> whereClauses = new ArrayList<>();
        if (component.getFilters() != null && !component.getFilters().isBlank()) {
            // 只卡这一侧，不影响另一侧——这正是拆成两个子查询的意义。
            whereClauses.add("(" + component.getFilters() + ")");
        }
        if (timeColumnRef != null && request.timeFrom() != null && !request.timeFrom().isBlank()) {
            whereClauses.add(timeColumnRef + " >= '" + escapeLiteral(request.timeFrom()) + "'");
        }
        if (timeColumnRef != null && request.timeTo() != null && !request.timeTo().isBlank()) {
            whereClauses.add(timeColumnRef + " < '" + escapeLiteral(request.timeTo()) + "'");
        }
        if (!whereClauses.isEmpty()) {
            sql.append("  WHERE ").append(String.join("\n    AND ", whereClauses)).append("\n");
        }
        if (!groupByItems.isEmpty()) {
            sql.append("  GROUP BY ").append(String.join(", ", groupByItems));
        }
        return sql.toString().stripTrailing();
    }

    private static String indent(String sql) {
        return sql.lines().map(line -> "  " + line).collect(Collectors.joining("\n"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record GroupKey(String expr, String alias) {
    }

    // ---------------------------------------------------------------- 解析辅助

    private static ColumnRef requireIncludedColumn(GraphWorkspace workspace, Map<String, TableWorkspaceNode> included,
            String columnId, String fieldLabel) {
        ColumnWorkspaceNode column = workspace.findColumnById(columnId);
        if (column == null) {
            throw new MetricExpansionException("引用的列不存在（" + fieldLabel + "）: " + columnId);
        }
        String tableId = tableIdForColumn(columnId);
        TableWorkspaceNode table = included.get(tableId);
        if (table == null) {
            throw new MetricExpansionException(fieldLabel + " 所在表不在 FROM/JOIN 范围内: " + columnId
                    + "（表 " + tableId + " 没有通过 joinPath 加入，请检查 joinPath 是否覆盖了这张表）");
        }
        return new ColumnRef(table, column);
    }

    private static String qualifiedColumnRef(DatabaseStrategy dialect, ColumnRef ref) {
        return dialect.qualifyTableName(ref.table().getSchema(), ref.table().getName())
                + "." + dialect.quoteIdentifier(ref.column().getName());
    }

    private static TableWorkspaceNode requireTable(GraphWorkspace workspace, String tableId, String label) {
        if (tableId == null) {
            throw new MetricExpansionException("无法解析" + label + "：列 id 格式不正确");
        }
        TableWorkspaceNode table = workspace.getTables().get(tableId);
        if (table == null) {
            throw new MetricExpansionException(label + "不存在: " + tableId);
        }
        return table;
    }

    private static RelationWorkspaceEdge findRelation(GraphWorkspace workspace, String relationId) {
        if (relationId == null) {
            return null;
        }
        for (RelationWorkspaceEdge edge : workspace.getRelations()) {
            if (edge.getId().equals(relationId)) {
                return edge;
            }
        }
        return null;
    }

    /** {@code column:alias:schema.table.column -> table:alias:schema.table}；同类解析在
     * WorkspacePathFinder/SchemaActionCommand 各自都有一份私有实现，这里跟随同样的写法。 */
    private static String tableIdForColumn(String columnId) {
        if (columnId == null || !columnId.startsWith("column:")) {
            return null;
        }
        String[] parts = columnId.split(":", 3);
        if (parts.length < 3) {
            return null;
        }
        int lastDot = parts[2].lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        return "table:" + parts[1] + ":" + parts[2].substring(0, lastDot);
    }

    private static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }

    private record JoinClause(MetricRecord.MetricJoinType joinType, TableWorkspaceNode table, String onExpression) {
    }

    private record ColumnRef(TableWorkspaceNode table, ColumnWorkspaceNode column) {
    }
}
