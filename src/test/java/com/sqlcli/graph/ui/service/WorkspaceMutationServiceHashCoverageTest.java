package com.sqlcli.graph.ui.service;

import com.sqlcli.graph.workspace.ChangeOperation;
import com.sqlcli.graph.workspace.ChangeRecord;
import com.sqlcli.graph.workspace.GraphActor;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import com.sqlcli.graph.workspace.WorkspaceValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 守住 {@code WorkspaceMutationService.hashWorkspace} 的结构性保证：新加一个 {@link GraphWorkspace}
 * 集合字段，不需要（也没有地方可以）手写登记，天然被纳入变更检测哈希。
 *
 * <p>{@link #everyNonExcludedFieldChangesTheHash()} 不针对具体字段名——它反射枚举
 * {@code GraphWorkspace} 的实例字段，逐个单独改动，断言哈希必须变化。往 {@code GraphWorkspace}
 * 加一个新的 Map/List 集合字段，这个测试自动把它纳入覆盖范围，不需要改测试本身；
 * 如果哈希实现哪天退化回手写清单又漏登记了新字段，这里就会红——
 * "忘了登记"曾经真的发生过：metrics 漏登记导致 {@code schema add-metric} 退出码 0 却不落盘。
 *
 * <p>{@link #manifestBookkeepingAndChangesDoNotAffectHash()} 是反证：{@code manifest} 的记账
 * 字段（revision/updatedAt/stats）和 {@code changes} 流水是刻意排除在哈希之外的（原因见
 * {@code WorkspaceMutationService.HASH_EXCLUDED_FIELDS} 上的 javadoc），单独改动不应该
 * 影响哈希——钉住这个决策，防止以后有人把它们"顺手"改成整体纳入。
 */
class WorkspaceMutationServiceHashCoverageTest {

    @TempDir Path temp;

    private String previousHome;
    private WorkspaceMutationService service;
    private Method hashWorkspace;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("sqlcli.home");
        System.setProperty("sqlcli.home", temp.resolve("home").toString());
        service = new WorkspaceMutationService(
                new GraphWorkspaceStore(temp), new WorkspaceValidator(), new WorkspaceLockManager());
        hashWorkspace = WorkspaceMutationService.class.getDeclaredMethod("hashWorkspace", GraphWorkspace.class);
        hashWorkspace.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        if (previousHome == null) {
            System.clearProperty("sqlcli.home");
        } else {
            System.setProperty("sqlcli.home", previousHome);
        }
    }

    /**
     * 钉住排除清单本身只有这两项——防止有人为了图省事把新字段悄悄塞进
     * {@code HASH_EXCLUDED_FIELDS} 绕过下面 {@link #everyNonExcludedFieldChangesTheHash()}
     * 的检查（那个测试只验证"未排除的字段"，排除清单本身撒谎它是不认的）。
     * 加新的排除项必须改这里，同时把理由写进实现的 javadoc。
     */
    @Test
    void exclusionListIsExactlyChangesAndManifest() throws Exception {
        assertEquals(Set.of("changes", "manifest"), hashExcludedFields(),
                "排除项变化必须是一次显式决策，改这个断言前先在 hashWorkspace 的 javadoc 里写清楚为什么");
    }

    @Test
    void everyNonExcludedFieldChangesTheHash() throws Exception {
        Set<String> excluded = hashExcludedFields();
        for (Field field : GraphWorkspace.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || excluded.contains(field.getName())) {
                continue;
            }
            field.setAccessible(true);
            GraphWorkspace workspace = GraphWorkspace.create("hash-coverage", "mysql");
            String before = hash(workspace);
            mutate(field, workspace);
            String after = hash(workspace);
            assertNotEquals(before, after, "字段 " + field.getName()
                    + " 改动后哈希必须变化，否则这个集合的写入会被 no-op 短路悄悄吞掉");
        }
    }

    @Test
    void manifestBookkeepingAndChangesDoNotAffectHash() throws Exception {
        GraphWorkspace workspace = GraphWorkspace.create("hash-coverage-manifest", "mysql");
        String before = hash(workspace);

        workspace.getManifest().incrementRevision();
        workspace.getManifest().touch();
        workspace.getChanges().add(ChangeRecord.create("hash-coverage-manifest",
                ChangeOperation.update, "x", GraphActor.human));

        String after = hash(workspace);
        assertEquals(before, after, "manifest 的记账字段与 changes 流水不参与 no-op 判断（原因见实现 javadoc）");
    }

    @SuppressWarnings("unchecked")
    private Set<String> hashExcludedFields() throws Exception {
        Field excludedField = WorkspaceMutationService.class.getDeclaredField("HASH_EXCLUDED_FIELDS");
        excludedField.setAccessible(true);
        return (Set<String>) excludedField.get(null);
    }

    private String hash(GraphWorkspace workspace) throws Exception {
        return (String) hashWorkspace.invoke(service, workspace);
    }

    @SuppressWarnings("unchecked")
    private void mutate(Field field, GraphWorkspace workspace) throws Exception {
        Object value = field.get(workspace);
        if (value instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).put("__hash_coverage_probe__", null);
        } else if (value instanceof List<?> list) {
            ((List<Object>) list).add(null);
        } else {
            // 单值字段（如 dataSource）：置空就是一次可观察的改动
            field.set(workspace, null);
        }
    }
}
