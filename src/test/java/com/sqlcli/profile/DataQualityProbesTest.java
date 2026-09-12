package com.sqlcli.profile;

import com.sqlcli.graph.eval.GraphFinding;
import com.sqlcli.graph.workspace.ColumnValueHints;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.RelationCardinality;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四条数据层探针。判据部分喂手搭的 {@link ColumnProfile}，连库部分用内存 SQLite——
 * 不碰任何真实数据库（任务硬约束），也不需要配别名。
 */
class DataQualityProbesTest {

    private static final String ALIAS = "dq";
    private static final String SCHEMA = "main";

    // ------------------------------------------------------------ data.column-unused

    @Test
    void allNullColumnIsUnused() {
        List<GraphFinding> findings = DataQualityProbes.columnUnused(
                profileOf(column("remark", 1000, 0, 0, null, 0)), table(), ALIAS);

        assertEquals(1, findings.size());
        assertEquals(GraphFinding.Severity.warning, findings.get(0).severity(),
                "数据层的发现是「值得看」，不是「一定错」");
        assertTrue(findings.get(0).message().contains("整列全为 NULL"), findings.get(0).message());
        assertTrue(findings.get(0).remediation().contains("schema edit --column"),
                "每条 finding 必须带一条能直接粘的命令");
    }

    /**
     * E1 交付说明的「已知没做的」第一条就是这个缺口：当时只判得到 distinct == 1（100%），
     * 因为 {@link ColumnProfile} 不带每个值的计数。补上 {@code topCount} 之后 99% 才判得动。
     */
    @Test
    void ninetyNinePercentInOneValueIsUnused() {
        List<GraphFinding> findings = DataQualityProbes.columnUnused(
                profileOf(column("del_flag", 1000, 1000, 3, "0", 990)), table(), ALIAS);

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("99%"), findings.get(0).message());
        assertTrue(findings.get(0).message().contains("（0）"), "占大头的取值要写出来，否则没法判断");
    }

    /** 阈值是**比例**不是绝对基数：98% 还有 20 行在写别的值，那不是「没在用」。 */
    @Test
    void ninetyEightPercentIsNotEnough() {
        assertTrue(DataQualityProbes.columnUnused(
                profileOf(column("del_flag", 1000, 1000, 3, "0", 980)), table(), ALIAS).isEmpty());
    }

    /** 采样行数太少就什么都不说——20 行里全是同一个值说明不了任何事。 */
    @Test
    void tinySampleYieldsNoConclusion() {
        assertTrue(DataQualityProbes.columnUnused(
                profileOf(column("del_flag", 20, 20, 1, "0", 20)), table(), ALIAS).isEmpty());
    }

    /** 敏感列的 {@code topValue} 恒为 null，占比照报、取值不报。 */
    @Test
    void sensitiveColumnReportsTheRatioWithoutTheValue() {
        List<GraphFinding> findings = DataQualityProbes.columnUnused(
                profileOf(column("phone", 1000, 1000, 2, null, 1000)), table(), ALIAS);

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("100%"), findings.get(0).message());
        assertTrue(findings.get(0).message().contains("是同一个取值，"), "不能把取值本身漏出去");
    }

    /** 数量闸门跟 {@code schema eval} 是同一道，超过 50 条就换成「这个探针用错了对象」。 */
    @Test
    void tooManyFindingsCollapseIntoOne() {
        List<ColumnProfile> columns = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            columns.add(column("c" + i, 1000, 0, 0, null, 0));
        }
        List<GraphFinding> gated = com.sqlcli.graph.eval.GraphEvaluator.gate("data.column-unused",
                DataQualityProbes.columnUnused(
                        new TableProfile(SCHEMA, "orders", 1000, null, Instant.now(), columns),
                        table(), ALIAS));

        assertEquals(1, gated.size());
        assertTrue(gated.get(0).message().contains("超过闸门 50"), gated.get(0).message());
    }

    // -------------------------------------------------------- data.enum-undeclared

    @Test
    void valueOutsideTheGraphEnumIsAHardError() {
        TableWorkspaceNode table = table();
        declareEnum(table, "status", List.of("0=新建", "1=完成"));
        ColumnProfile status = new ColumnProfile("status", false, null, 1000, 1000, 0.0, 3, true,
                List.of("0", "1", "9"), null, null, null, "0", 600);

        List<GraphFinding> findings = DataQualityProbes.enumUndeclared(profileOf(status), table, ALIAS);

        assertEquals(1, findings.size());
        assertEquals(GraphFinding.Severity.error, findings.get(0).severity(),
                "agent 按图谱写 WHERE ... IN 会静默漏掉这批数据，和 E1 一样是硬错误");
        assertTrue(findings.get(0).message().contains("[9]"), findings.get(0).message());
        assertTrue(findings.get(0).remediation().contains("schema value-domain"),
                "修法是回到值域三源交叉，不是直接改图谱");
    }

    @Test
    void enumMatchingTheGraphSaysNothing() {
        TableWorkspaceNode table = table();
        declareEnum(table, "status", List.of("0=新建", "1=完成"));
        ColumnProfile status = new ColumnProfile("status", false, null, 1000, 1000, 0.0, 2, true,
                List.of("0", "1"), null, null, null, "0", 600);

        assertTrue(DataQualityProbes.enumUndeclared(profileOf(status), table, ALIAS).isEmpty());
    }

    /** 图谱压根没声明值域时不归这条探针管——那是「缺一份值域」，`schema value-domain` 的活。 */
    @Test
    void columnWithoutADeclaredEnumIsNotThisProbesJob() {
        ColumnProfile status = new ColumnProfile("status", false, null, 1000, 1000, 0.0, 3, true,
                List.of("0", "1", "9"), null, null, null, "0", 600);

        assertTrue(DataQualityProbes.enumUndeclared(profileOf(status), table(), ALIAS).isEmpty());
    }

    // ----------------------------------------------------------------- 连库那一半

    private Connection connection;

    @BeforeEach
    void openDatabase() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE users (id INTEGER, name TEXT)");
            for (int id = 1; id <= 3; id++) {
                st.execute("INSERT INTO users VALUES (" + id + ", 'u" + id + "')");
            }
            st.execute("CREATE TABLE orders (id INTEGER, buyer_id INTEGER, status TEXT,"
                    + " memo TEXT, create_time DATETIME)");
            // 40 行 > MIN_SAMPLE(30)：memo 全 NULL、status 里混进一个图谱没声明的 9、
            // 后 10 行的 buyer_id 指向不存在的 99、create_time 全在 2019 年
            for (int i = 0; i < 40; i++) {
                String buyer = i < 30 ? String.valueOf(i % 3 + 1) : "99";
                String status = i == 0 ? "9" : "0";
                String createTime = LocalDate.of(2019, 1, 1).plusDays(i) + " 08:00:00";
                st.execute("INSERT INTO orders VALUES (" + i + ", " + buyer + ", '" + status
                        + "', NULL, '" + createTime + "')");
            }
        }
    }

    @AfterEach
    void closeDatabase() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void endToEndOnSqliteFindsOrphansDeadTableAndUnusedColumn() {
        GraphWorkspace workspace = sqliteWorkspace();
        TableWorkspaceNode orders = workspace.getTableByQualifiedName(SCHEMA + ".orders");

        List<GraphFinding> findings = DataQualityProbes.run(connection, "sqlite", workspace, orders,
                DataQualityProbes.DEFAULT_DEAD_MONTHS, ProfileOptions.defaults());

        assertNotNull(finding(findings, "data.column-unused"), "memo 整列 NULL\n" + findings);
        assertTrue(finding(findings, "data.column-unused").message().contains("memo"), findings.toString());

        GraphFinding enumFinding = finding(findings, "data.enum-undeclared");
        assertNotNull(enumFinding, "status 里有图谱没声明的 9\n" + findings);
        assertEquals(GraphFinding.Severity.error, enumFinding.severity());

        GraphFinding orphan = finding(findings, "data.orphan-fk");
        assertNotNull(orphan, "后 10 行的 buyer_id 指向不存在的 99\n" + findings);
        assertTrue(orphan.message().contains("10/40"), orphan.message());
        assertTrue(orphan.message().contains("25.0%"), orphan.message());

        GraphFinding dead = finding(findings, "data.table-dead");
        assertNotNull(dead, "create_time 最大值在 2019 年\n" + findings);
        assertTrue(dead.message().contains("2019-02-09"), dead.message());
    }

    /** {@code --months} 放宽到足以覆盖这批数据时，表死这条就不该再报。 */
    @Test
    void aGenerousMonthsWindowStopsTheDeadTableFinding() {
        GraphWorkspace workspace = sqliteWorkspace();
        TableWorkspaceNode orders = workspace.getTableByQualifiedName(SCHEMA + ".orders");
        int monthsSince2019 = (LocalDate.now().getYear() - 2019) * 12 + 24;

        List<GraphFinding> findings = DataQualityProbes.run(connection, "sqlite", workspace, orders,
                monthsSince2019, ProfileOptions.defaults());

        assertNull(finding(findings, "data.table-dead"), findings.toString());
    }

    /** 授权清单里必须出现真正会跑的那几条 SQL——批准的是查询，不是一个命令名。 */
    @Test
    void plannedQueriesListTheStatementsThatWillActuallyRun() {
        GraphWorkspace workspace = sqliteWorkspace();
        TableWorkspaceNode orders = workspace.getTableByQualifiedName(SCHEMA + ".orders");

        String plan = String.join("\n",
                DataQualityProbes.plannedQueries("sqlite", workspace, orders, ProfileOptions.defaults()));

        assertTrue(plan.contains("SELECT COUNT(*) FROM \"main\".\"orders\""), plan);
        assertTrue(plan.contains("COUNT(DISTINCT"), plan);
        assertTrue(plan.contains("MAX(\"create_time\")"), plan);
        assertTrue(plan.contains("NOT IN (SELECT \"id\" FROM \"main\".\"users\""), plan);
    }

    /** 候选边（未验证）不查：那是「这条边编错了没有」，归评审队列，不是数据质量。 */
    @Test
    void unverifiedRelationsAreNotProbed() {
        GraphWorkspace workspace = sqliteWorkspace();
        workspace.getRelations().get(0).setVerified(false);
        TableWorkspaceNode orders = workspace.getTableByQualifiedName(SCHEMA + ".orders");

        List<GraphFinding> findings = DataQualityProbes.run(connection, "sqlite", workspace, orders,
                DataQualityProbes.DEFAULT_DEAD_MONTHS, ProfileOptions.defaults());

        assertNull(finding(findings, "data.orphan-fk"), findings.toString());
    }

    // ------------------------------------------------------------------------ 辅助

    private static GraphFinding finding(List<GraphFinding> findings, String probe) {
        return findings.stream().filter(f -> probe.equals(f.probe())).findFirst().orElse(null);
    }

    private GraphWorkspace sqliteWorkspace() {
        GraphWorkspace workspace = GraphWorkspace.create(ALIAS, "sqlite");
        TableWorkspaceNode orders = TableWorkspaceNode.create(ALIAS, SCHEMA, "orders", GraphActor.extractor);
        for (String name : List.of("id", "buyer_id", "status", "memo", "create_time")) {
            orders.getColumns().add(ColumnWorkspaceNode.create(name));
        }
        declareEnum(orders, "status", List.of("0=新建", "1=完成"));
        workspace.getTables().put(orders.getId(), orders);

        TableWorkspaceNode users = TableWorkspaceNode.create(ALIAS, SCHEMA, "users", GraphActor.extractor);
        users.getColumns().add(ColumnWorkspaceNode.create("id"));
        workspace.getTables().put(users.getId(), users);

        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(ALIAS, RelationType.foreign_key,
                GraphIds.columnId(ALIAS, SCHEMA, "orders", "buyer_id"),
                GraphIds.columnId(ALIAS, SCHEMA, "users", "id"), GraphActor.extractor);
        edge.setCardinality(RelationCardinality.many_to_one);
        edge.setConfidence(1.0);
        edge.setVerified(true);
        edge.setStatus(GraphStatus.verified);
        workspace.getRelations().add(edge);
        return workspace;
    }

    private static TableWorkspaceNode table() {
        TableWorkspaceNode table = TableWorkspaceNode.create(ALIAS, SCHEMA, "orders", GraphActor.extractor);
        table.getColumns().add(ColumnWorkspaceNode.create("status"));
        return table;
    }

    private static void declareEnum(TableWorkspaceNode table, String column, List<String> values) {
        ColumnWorkspaceNode node = table.findColumn(column);
        ColumnValueHints hints = new ColumnValueHints();
        hints.setEnumValues(new ArrayList<>(values));
        node.setValueHints(hints);
    }

    private static ColumnProfile column(String name, long rows, long nonNull, long distinct,
            String topValue, long topCount) {
        double nullRatio = rows == 0 ? 0.0 : (double) (rows - nonNull) / rows;
        return new ColumnProfile(name, false, null, rows, nonNull, nullRatio, distinct, false,
                List.of(), null, null, null, topValue, topCount);
    }

    private static TableProfile profileOf(ColumnProfile... columns) {
        return new TableProfile(SCHEMA, "orders", 1000, null, Instant.now(), List.of(columns));
    }
}
