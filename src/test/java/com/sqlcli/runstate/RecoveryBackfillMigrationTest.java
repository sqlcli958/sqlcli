package com.sqlcli.runstate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回滚脚本从文件搬进运行库的一次性回填。
 *
 * <p>为什么值得一条测试：脚本改存 SQLite 那次只切了写路径，老记录仍然只有文件路径，
 * 于是「恢复文件必须永远留着」和「兼容读分支必须永远留着」两笔债一直挂着——
 * 还让人打开库看见 file_path 就以为脚本至今存在文件里。这条钉住迁移真的把内容搬过来了。
 */
class RecoveryBackfillMigrationTest {

    @TempDir Path temp;

    /** 升级前的表形态：只有 file_path，没有 rollback_sql / backup_text。 */
    private void createLegacyDatabase(Path db, String filePath) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE recovery_artifact (
                      id           INTEGER PRIMARY KEY AUTOINCREMENT,
                      execution_id INTEGER,
                      file_path    TEXT    NOT NULL,
                      checksum     TEXT,
                      status       TEXT    NOT NULL,
                      created_at   INTEGER NOT NULL
                    )""");
            st.execute("INSERT INTO recovery_artifact (execution_id, file_path, checksum, status, created_at)"
                    + " VALUES (7, '" + filePath.replace("'", "''") + "', 'abc', 'saved', 1000)");
            st.execute("PRAGMA user_version=1");
        }
    }

    private Path writeLegacyRecoveryFile() throws Exception {
        Path file = temp.resolve("legacy-recovery.sql");
        Files.writeString(file, """
                -- Recovery SQL for DELETE on sys_menu
                -- Alias: demo
                -- Affected rows: 2

                -- Executed SQL:
                delete from sys_menu where id in ('a', 'b')

                -- Original Data:
                -- id='a', name='首页'
                -- id='b', name='报表'

                -- Recovery SQL:
                INSERT INTO sys_menu (id, name) VALUES ('a', '首页');
                INSERT INTO sys_menu (id, name) VALUES ('b', '报表');
                """);
        return file;
    }

    @Test
    void legacyFileContentIsMovedIntoTheDatabaseAndTheDeadColumnsAreDropped() throws Exception {
        Path db = temp.resolve("sqlcli.db");
        Path legacy = writeLegacyRecoveryFile();
        createLegacyDatabase(db, legacy.toString());

        // 第一次读就会触发 ensureSchema → migrate → 回填
        RunStateStore store = new RunStateStore(db, temp.resolve("history"));
        RecoveryArtifactRow row = store.findRecovery(7);

        assertNotNull(row, "老记录迁移后仍然找得到");
        assertTrue(row.hasScript(), "回滚段已经搬进库里");
        assertTrue(row.rollbackSql().startsWith("INSERT INTO sys_menu"), row.rollbackSql());
        assertEquals(2, row.rollbackSql().lines().count(), "两条 INSERT 都在");
        assertNotNull(row.backupText(), "执行前的原始行也要搬过来");
        assertTrue(row.backupText().contains("首页"), row.backupText());
        assertFalse(row.backupText().contains("--"), "原始行前面的注释前缀要剥掉：" + row.backupText());

        assertFalse(hasColumn(db, "file_path"), "回填完就该删掉，留着会让人以为脚本还在文件里");
        assertFalse(hasColumn(db, "checksum"), "校验文件内容的列，内容进库后没有意义");
    }

    /** 文件已经被人删了：状态标成 missing，而不是留一个指向空气的路径。 */
    @Test
    void aVanishedLegacyFileIsRecordedAsMissing() throws Exception {
        Path db = temp.resolve("sqlcli.db");
        createLegacyDatabase(db, temp.resolve("gone.sql").toString());

        RunStateStore store = new RunStateStore(db, temp.resolve("history"));
        RecoveryArtifactRow row = store.findRecovery(7);

        assertNotNull(row);
        assertFalse(row.hasScript(), "文件没了就没有脚本可搬");
        assertEquals("missing", statusOf(db), "这条记录不可用的事实要留下来");
    }

    private boolean hasColumn(Path db, String column) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(recovery_artifact)")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    private String statusOf(Path db) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT status FROM recovery_artifact WHERE execution_id = 7")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
