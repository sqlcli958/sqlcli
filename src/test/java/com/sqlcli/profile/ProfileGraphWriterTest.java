package com.sqlcli.profile;

import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphIds;
import com.sqlcli.graph.workspace.GraphStatus;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.RelationCardinality;
import com.sqlcli.graph.workspace.RelationType;
import com.sqlcli.graph.workspace.RelationWorkspaceEdge;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProfileGraphWriter} 的规划逻辑——全部用手搭的内存图谱对象，不连库
 * （剖析引擎本身的 DB 交互由 {@link TableProfilerTest} 覆盖）。
 */
class ProfileGraphWriterTest {

    private static final String ALIAS = "test-alias";
    private static final String SCHEMA = "public";
    private static final String TABLE = "t_customer";

    private GraphWorkspace workspace;
    private TableWorkspaceNode table;

    @BeforeEach
    void setUp() {
        workspace = GraphWorkspace.create(ALIAS, "generic");
        table = TableWorkspaceNode.create(ALIAS, SCHEMA, TABLE, GraphActor.extractor);
        workspace.getTables().put(table.getId(), table);
    }

    /** 声明可空（{@code fromOptional=true}）、来源是导入时的 {@code inferred}——真库里的常态。 */
    private RelationWorkspaceEdge addForeignKeyEdge(String fromColumn) {
        table.getColumns().add(ColumnWorkspaceNode.create(fromColumn));
        RelationWorkspaceEdge edge = RelationWorkspaceEdge.create(ALIAS, RelationType.foreign_key,
                GraphIds.columnId(ALIAS, SCHEMA, TABLE, fromColumn),
                GraphIds.columnId(ALIAS, SCHEMA, "t_parent", "id"), GraphActor.extractor);
        edge.setCardinality(RelationCardinality.many_to_one);
        edge.setConfidence(1.0);
        edge.setVerified(true);
        edge.setStatus(GraphStatus.verified);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_FROM_OPTIONAL, true);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_TO_OPTIONAL, true);
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_OPTIONALITY_SOURCE,
                RelationWorkspaceEdge.OPTIONALITY_SOURCE_INFERRED);
        workspace.getRelations().add(edge);
        return edge;
    }

    /** 采样覆盖整表（rowsSampled == rowCount）的一列剖析结果。 */
    private TableProfile fullScan(String column, long rows, double nullRatio) {
        long nonNull = Math.round(rows * (1 - nullRatio));
        return new TableProfile(SCHEMA, TABLE, rows, null, java.time.Instant.now(),
                List.of(new ColumnProfile(column, false, null, rows, nonNull, nullRatio, 10, false,
                        List.of(), null, null, null, null, 0)));
    }

    private List<ProfileGraphWriter.OptionalityUpgrade> plan(TableProfile profile) {
        return ProfileGraphWriter.planOptionalityUpgrades(workspace, ALIAS, profile);
    }

    @Test
    void measuredZeroNullsUpgradesInferredOptionalityToProfiled() {
        RelationWorkspaceEdge edge = addForeignKeyEdge("parent_id");

        List<ProfileGraphWriter.OptionalityUpgrade> upgrades = plan(fullScan("parent_id", 100, 0.0));

        assertEquals(1, upgrades.size());
        assertEquals(edge.getId(), upgrades.get(0).relationId());
        assertEquals("parent_id", upgrades.get(0).column());
        // 审批人要能从这句话判断该不该放行，光给一个布尔值批不下去
        assertTrue(upgrades.get(0).reason().contains("100"), upgrades.get(0).reason());

        ProfileGraphWriter.applyOptionality(workspace, upgrades.get(0));

        assertEquals(Boolean.FALSE, edge.getFromOptional(), "实测零空值，改判为不可选");
        assertEquals(RelationWorkspaceEdge.OPTIONALITY_SOURCE_PROFILED,
                edge.getAttributes().get(RelationWorkspaceEdge.ATTR_OPTIONALITY_SOURCE));
        // to 端不是单表剖析能回答的，维持导入时保守的初值
        assertEquals(Boolean.TRUE, edge.getToOptional());
    }

    @Test
    void measuredNullsKeepOptionalTrueButStillRecordTheSource() {
        RelationWorkspaceEdge edge = addForeignKeyEdge("parent_id");

        List<ProfileGraphWriter.OptionalityUpgrade> upgrades = plan(fullScan("parent_id", 100, 0.3));

        assertEquals(1, upgrades.size(), "值没变但来源从声明变成实测，仍然值得记一笔");
        ProfileGraphWriter.applyOptionality(workspace, upgrades.get(0));

        assertEquals(Boolean.TRUE, edge.getFromOptional());
        assertEquals(RelationWorkspaceEdge.OPTIONALITY_SOURCE_PROFILED,
                edge.getAttributes().get(RelationWorkspaceEdge.ATTR_OPTIONALITY_SOURCE));
    }

    /**
     * value-domain 是会被反复跑的命令。同一份实测结论第二次不能再提一条——
     * 否则每跑一次就往待审批队列里塞一份重复。
     */
    @Test
    void secondRunWithSameMeasurementProposesNothing() {
        addForeignKeyEdge("parent_id");
        TableProfile profile = fullScan("parent_id", 100, 0.0);

        ProfileGraphWriter.applyOptionality(workspace, plan(profile).get(0));

        assertTrue(plan(profile).isEmpty(), "结论没变就不该再提条目");
    }

    /**
     * 一千万行表的一万行样本证明不了「这一列没有 NULL」。据此把 LEFT 改成 INNER
     * 正好造出这个功能要防的错：不报错、有结果、数偏少。
     */
    @Test
    void zeroNullsInAPartialSampleIsNotEnoughToClaimNotNull() {
        addForeignKeyEdge("parent_id");
        TableProfile sampled = new TableProfile(SCHEMA, TABLE, 10_000_000, null, java.time.Instant.now(),
                List.of(new ColumnProfile("parent_id", false, null, 10_000, 10_000, 0.0, 10, false,
                        List.of(), null, null, null, null, 0)));

        assertTrue(plan(sampled).isEmpty());
    }

    /** 反过来：样本里出现过 NULL，一个反例就足以证明这一列可空，不需要全表。 */
    @Test
    void oneNullInAPartialSampleIsEnoughToClaimOptional() {
        addForeignKeyEdge("parent_id");
        TableProfile sampled = new TableProfile(SCHEMA, TABLE, 10_000_000, null, java.time.Instant.now(),
                List.of(new ColumnProfile("parent_id", false, null, 10_000, 9_999, 0.0001, 10, false,
                        List.of(), null, null, null, null, 0)));

        assertEquals(1, plan(sampled).size());
    }

    /** 行数拿不到（超时 / 权限）就不知道分母，同样不能断言全表无 NULL。 */
    @Test
    void missingRowCountBlocksTheNotNullClaim() {
        addForeignKeyEdge("parent_id");
        TableProfile noRowCount = new TableProfile(SCHEMA, TABLE, -1, "count timeout",
                java.time.Instant.now(),
                List.of(new ColumnProfile("parent_id", false, null, 100, 100, 0.0, 10, false,
                        List.of(), null, null, null, null, 0)));

        assertTrue(plan(noRowCount).isEmpty());
    }

    @Test
    void humanConfirmedSourceIsNeverOverwrittenByMeasurement() {
        RelationWorkspaceEdge edge = addForeignKeyEdge("parent_id");
        edge.getAttributes().put(RelationWorkspaceEdge.ATTR_OPTIONALITY_SOURCE, "confirmed");

        assertTrue(plan(fullScan("parent_id", 100, 0.0)).isEmpty(),
                "人已表过态的值不能被测量值盖掉");
    }

    /** 非外键边不参与：optionality 是外键约束上的属性，推断出来的边没有这个落点。 */
    @Test
    void nonForeignKeyEdgesAreLeftAlone() {
        table.getColumns().add(ColumnWorkspaceNode.create("parent_id"));
        RelationWorkspaceEdge observed = RelationWorkspaceEdge.create(ALIAS, RelationType.join_observed,
                GraphIds.columnId(ALIAS, SCHEMA, TABLE, "parent_id"),
                GraphIds.columnId(ALIAS, SCHEMA, "t_parent", "id"), GraphActor.agent);
        observed.setConfidence(0.6);
        workspace.getRelations().add(observed);

        assertTrue(plan(fullScan("parent_id", 100, 0.0)).isEmpty());
    }

    @Test
    void skippedColumnProfileProducesNothing() {
        addForeignKeyEdge("parent_id");
        TableProfile profile = new TableProfile(SCHEMA, TABLE, 100, null, java.time.Instant.now(),
                List.of(ColumnProfile.skipped("parent_id", "聚合查询失败")));

        assertTrue(plan(profile).isEmpty());
    }

    @Test
    void tableNotInGraphIsSilentlyIgnored() {
        TableProfile profile = new TableProfile("other_schema", "unknown_table", 5, null,
                java.time.Instant.now(),
                List.of(new ColumnProfile("status", false, null, 5, 5, 0.0, 2, true,
                        List.of("a", "b"), null, null, null, null, 0)));

        assertTrue(ProfileGraphWriter.planOptionalityUpgrades(workspace, ALIAS, profile).isEmpty());
    }
}
