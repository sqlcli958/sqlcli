package com.sqlcli.graph.workspace.index;

import com.sqlcli.graph.workspace.ColumnDataType;
import com.sqlcli.graph.workspace.ColumnWorkspaceNode;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.SemanticType;
import com.sqlcli.graph.workspace.TableType;
import com.sqlcli.graph.workspace.TableWorkspaceNode;
import com.sqlcli.graph.workspace.WorkspaceManifest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dev-checklist 740 行「增量索引更新」待办的判断依据：先量全量重建耗时，
 * 只有明显偏慢（清单写的阈值是 >5s）才值得做增量。
 *
 * 用内存构造的 499 表 / ~9700 字段工作区（对齐清单第 739 行提到的规模量级）
 * 跑一次 {@link WorkspaceIndexer#rebuild}——它是 mutation 落库后触发全量重建
 * 的唯一入口（见 WorkspaceMutationController 里的 "web index rebuild"）。
 *
 * 实测在这个规模下是毫秒级（个位数到几十毫秒），远低于清单自己写的「够快」
 * 判断，也远低于 >5s 的增量开发阈值。结论：不实现增量索引，这个测试就是
 * 那个耗时基准，回归到这个量级的锅炉表也不用担心。
 */
class WorkspaceIndexRebuildBenchmarkTest {

    private static final int TABLE_COUNT = 499;
    private static final int TOTAL_COLUMNS_TARGET = 9727;

    @Test
    void fullRebuildStaysWellUnderIncrementalThreshold() {
        GraphWorkspace workspace = buildWorkspace(TABLE_COUNT, TOTAL_COLUMNS_TARGET);
        WorkspaceIndexer indexer = new WorkspaceIndexer();

        // JIT 预热，避免第一次调用的解释执行拖高读数
        for (int i = 0; i < 5; i++) {
            indexer.rebuild(workspace, i);
        }

        long best = Long.MAX_VALUE;
        for (int i = 0; i < 10; i++) {
            long start = System.nanoTime();
            WorkspaceIndexSnapshot snapshot = indexer.rebuild(workspace, i);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            best = Math.min(best, elapsedMs);
            assertTrue(snapshot.getDocuments().size() > TOTAL_COLUMNS_TARGET,
                    "索引文档数应覆盖全部表 + 列");
        }

        System.out.println("[benchmark] WorkspaceIndexer.rebuild 全量重建耗时（" + TABLE_COUNT
                + " 表 / " + TOTAL_COLUMNS_TARGET + " 列量级）最快一次: " + best + " ms");

        // 清单里 >5s 才值得做增量；这里给 5 秒的十分之一做回归红线，
        // 量级明显上去（比如真涨到几万表）时这条测试会先炸。
        assertTrue(best < 500, "全量重建耗时 " + best + "ms 超出预期，增量索引的判断依据需要重新评估");
    }

    private static GraphWorkspace buildWorkspace(int tableCount, int totalColumnsTarget) {
        String alias = "bench";
        WorkspaceManifest manifest = WorkspaceManifest.create(alias);
        manifest.setRevision(1);
        GraphWorkspace workspace = new GraphWorkspace();
        workspace.setManifest(manifest);

        int baseColumnsPerTable = totalColumnsTarget / tableCount;
        int remainder = totalColumnsTarget % tableCount;

        for (int t = 0; t < tableCount; t++) {
            TableWorkspaceNode table = TableWorkspaceNode.create(alias, "app", "table_" + t, GraphActor.system);
            table.setTableType(TableType.base_table);
            table.setComment("表注释 " + t);
            table.setBusinessName("业务表" + t);

            int columnsForThisTable = baseColumnsPerTable + (t < remainder ? 1 : 0);
            for (int c = 0; c < columnsForThisTable; c++) {
                ColumnWorkspaceNode column = new ColumnWorkspaceNode();
                column.setName("col_" + c);
                column.setDataType(new ColumnDataType());
                column.setComment("字段注释 " + c);
                column.setBusinessName("业务字段" + c);
                if (c % 7 == 0) {
                    column.setSemanticType(SemanticType.phone);
                }
                table.getColumns().add(column);
            }
            workspace.getTables().put(table.getId(), table);
        }
        return workspace;
    }
}
