package com.sqlcli.strategy;

import com.sqlcli.config.DatabaseConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * SQLite 方言策略。
 *
 * <p><b>和模板的三处偏离，都是 SQLite 本身的性质决定的，不是抄漏了：</b>
 *
 * <ol>
 *   <li><b>{@link #buildJdbcUrl}不走 host/port/database 结构化配置。</b>
 *       SQLite 连接是"选一个 db 文件"，没有 host、没有 port、没有账号密码，
 *       模板第 4.1 节要求的 host/port/database 结构对它不适用——这里改成从完整的
 *       {@code jdbcUrl=jdbc:sqlite:<path>}（CLI 向导/Web 表单落库的形式，见
 *       {@code AliasConfigValidator#validateSqlite}）或 {@link DatabaseConfig#getDatabase()}
 *       读文件路径，不新增配置项。
 *
 *       <p><b>文件是否存在的检查不放在这个方法里，放在 {@link #applyConnectionProperties}。</b>
 *       原因是 {@code DatabaseConfig#buildBaseJdbcUrl()} 只有在 {@code jdbcUrl} 为空时才会调用
 *       这个方法——而真实别名（无论是向导生成还是手写 YAML）{@code jdbcUrl} 永远是非空的
 *       （{@code AliasResolver#parseConfig} 对所有 JDBC 别名强制要求 {@code url} 字段），
 *       所以这个方法在真实连接路径上几乎不会被调用，检查写在这里等于没写。
 *       {@code applyConnectionProperties} 是 {@code ConnectionManager}（直连和连接池两条路径）
 *       在真正拿到连接前必经的方言钩子，检查放这里才对所有连接方式都生效。
 *   <li><b>{@link #defaultSchema}固定返回 {@code "main"}，不做探测。</b>
 *       实测 sqlite-jdbc 3.46.1.3 的 {@code DatabaseMetaData.getSchemas()}/{@code getCatalogs()}
 *       都返回空结果集，{@code getTables()} 里 TABLE_CAT/TABLE_SCHEM 恒为 null——这正是
 *       之前直接拿 {@link GenericDatabaseStrategy} 跑 {@code schema import} 只导到 0 张表的原因
 *       （discoverSchemas 按 JDBC schema 枚举，枚举出来是空的）。{@code PRAGMA database_list}
 *       证实 SQLite 自己确实有"schema"概念，只是不通过标准 JDBC 元数据暴露：主库固定叫
 *       {@code main}（ATTACH 上来的库才有别的名字）。图谱模型里固定用这个名字建
 *       {@code SchemaWorkspaceNode}，比用文件名更诚实，也和用户敲 {@code --schema main} 的
 *       直觉一致。
 *   <li><b>不获取表注释/字段注释。</b>SQLite 没有 {@code COMMENT} 语法，{@code sqlite_master.sql}
 *       里也不会有。这是正常情况，{@link #getTableDdl}和字段抽取都不尝试解析注释，
 *       图谱里 comment 留 null，靠人和 Agent 补 description/businessName。
 * </ol>
 *
 * <p>元数据抽取（schema/table/column/主键/外键）复用通用的
 * {@link com.sqlcli.graph.workspace.WorkspaceMetadataExtractor}——不像 ClickHouse 需要专门的
 * provider 查系统表，SQLite 标准 JDBC {@code DatabaseMetaData} 本身就够用（实测
 * {@code getColumns}/{@code getPrimaryKeys}/{@code getImportedKeys} 都正常工作，且驱动会自动把
 * {@code sqlite_*} 系统表标成 {@code SYSTEM TABLE} 类型，{@code getTables(..., {"TABLE","VIEW"})}
 * 天然把它们过滤掉）。唯一需要教会通用 provider 的是"SQLite 的 schema 固定是
 * main"——{@code WorkspaceMetadataExtractor#discoverSchemas} 里加了一个分支，没有另建整个类。
 * 复合外键分组：SQLite 返回的 {@code FK_NAME} 是空字符串而非 null，正好落进
 * {@code WorkspaceMetadataExtractor#groupByConstraint} 已有的"驱动不给 FK_NAME 时按 KEY_SEQ
 * 重置分组"退化路径，实测两列复合外键被正确分成一组（见
 * {@code SqliteWorkspaceMetadataIntegrationTest}）。
 */
public class SqliteDatabaseStrategy extends AbstractDatabaseStrategy {

    private static final String URL_PREFIX = "jdbc:sqlite:";
    /** 别名 params 里的开关名：显式允许在文件不存在时新建空库。默认关闭，见类注释和 buildJdbcUrl。 */
    private static final String PARAM_CREATE_IF_MISSING = "createIfMissing";

    @Override
    public String type() {
        return "sqlite";
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return DatabaseCapabilities.SQLITE_DEFAULTS();
    }

    @Override
    public SqlExecutionPolicy executionPolicy() {
        // SQLite 支持标准事务、标准 UPDATE/DELETE、可靠的 affected rows,
        // 和 MySQL/Oracle/PostgreSQL 走同一套标准 RDBMS 执行策略,不需要专门的策略常量。
        return SqlExecutionPolicy.STANDARD_RDBMS_POLICY;
    }

    @Override
    public String defaultSchema(DatabaseConfig config) {
        return "main";
    }

    /**
     * 从 jdbcUrl 或文件路径构建 SQLite JDBC URL——见类顶部偏离说明第 1 点。
     * 只负责拼字符串，不做文件存在性检查（检查在 {@link #applyConnectionProperties}）。
     * 路径统一转成正斜杠，和 CLI 向导/{@code AliasConfigValidator} 落库的形式保持一致，
     * Windows 反斜杠混进 jdbcUrl 字符串容易和转义规则搅在一起。
     */
    @Override
    public String buildJdbcUrl(DatabaseConfig config) {
        String file = resolveFile(config);
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException(
                    "sqlite 别名需要配置 jdbcUrl=jdbc:sqlite:<path> 或 database（.db 文件路径）");
        }
        if (isSpecialFile(file)) {
            // :memory: 或空字符串（匿名临时库）没有对应文件，原样透传。
            return URL_PREFIX + file;
        }
        Path path = Paths.get(file).toAbsolutePath().normalize();
        return URL_PREFIX + toForwardSlashes(path);
    }

    /**
     * 真正连接前必经的钩子（见类顶部说明为什么检查不放在 {@code buildJdbcUrl}）。
     * 文件不存在时明确拒绝，而不是让 sqlite-jdbc 静默新建一个空库。
     */
    @Override
    public void applyConnectionProperties(DatabaseConfig config, Properties properties) {
        String file = resolveFile(config);
        if (file == null || file.isBlank() || isSpecialFile(file)) {
            return;
        }
        ensureFileExists(Paths.get(file).toAbsolutePath().normalize(), config);
    }

    private String resolveFile(DatabaseConfig config) {
        if (config.getJdbcUrl() != null && !config.getJdbcUrl().isBlank()) {
            return extractFilePath(config.getJdbcUrl());
        }
        return config.getDatabase();
    }

    private String extractFilePath(String jdbcUrl) {
        if (!jdbcUrl.startsWith(URL_PREFIX)) {
            throw new IllegalArgumentException("sqlite jdbcUrl must start with " + URL_PREFIX + ": " + jdbcUrl);
        }
        return jdbcUrl.substring(URL_PREFIX.length());
    }

    private boolean isSpecialFile(String file) {
        return file.isBlank() || ":memory:".equalsIgnoreCase(file);
    }

    private String toForwardSlashes(Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
     * 新建空库这件事本身不是错误用法（临时库、测试库都有这个需求），但必须是显式开关：
     * 别名 {@code params} 里加 {@code createIfMissing: "true"} 才放行，默认关闭。
     */
    private void ensureFileExists(Path path, DatabaseConfig config) {
        if (Files.exists(path)) {
            return;
        }
        boolean allowCreate = "true".equalsIgnoreCase(
                config.getParams() == null ? null : config.getParams().get(PARAM_CREATE_IF_MISSING));
        if (allowCreate) {
            return;
        }
        throw new IllegalArgumentException(
                "SQLite 数据库文件不存在: " + path + "。"
                        + "sqlite-jdbc 对不存在的文件不报错，而是静默新建一个空库——"
                        + "多半是路径拼错，而不是真的要新建。"
                        + "确认路径无误，或者在别名 params 里显式加 createIfMissing: \"true\" 允许新建。");
    }

    /**
     * SQLite 没有 {@code SHOW CREATE TABLE}，DDL 直接存在 {@code sqlite_master.sql} 里，
     * 建表/建视图时怎么写的就原样存着。schema 固定是 main，非 main（比如 ATTACH 进来的库）
     * 才需要 {@code schema.sqlite_master} 限定，这里保留这个分支但不主动支持 ATTACH。
     */
    @Override
    public String getTableDdl(Connection conn, String tableName, String schemaName) throws SQLException {
        String prefix = schemaName != null && !schemaName.isBlank() && !"main".equalsIgnoreCase(schemaName)
                ? quoteIdentifier(schemaName) + "."
                : "";
        String sql = "SELECT sql FROM " + prefix + "sqlite_master WHERE type IN ('table','view') AND name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String ddl = rs.getString(1);
                    if (ddl != null) {
                        return ddl;
                    }
                }
                throw new SQLException("Table not found: " + tableName);
            }
        }
    }

    /**
     * 表清单直接用 JDBC {@code DatabaseMetaData}——sqlite-jdbc 实测会把 {@code sqlite_*}
     * 系统表自动标成 {@code SYSTEM TABLE}，{@code TABLE/VIEW} 类型过滤天然排除它们。
     * schema/catalog 参数传什么 sqlite-jdbc 都不看（TABLE_CAT/TABLE_SCHEM 恒为 null），
     * 这里只是把返回的 {@link TableInfo} 打上固定的 "main" 标签，不影响查询本身。
     */
    @Override
    public List<TableInfo> listTables(Connection conn, String schemaName, String pattern) throws SQLException {
        String effectiveSchema = schemaName != null && !schemaName.isBlank() ? schemaName : "main";
        String tablePattern = pattern != null && !pattern.isBlank() ? pattern : "%";
        List<TableInfo> tables = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getTables(null, null, tablePattern, new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                String tableType = rs.getString("TABLE_TYPE");
                // REMARKS 恒为 null：SQLite 没有 COMMENT 语法，这是正常情况不是抓取失败。
                tables.add(new TableInfo(tableName, effectiveSchema, tableType, null));
            }
        }
        return tables;
    }

    @Override
    public List<String> buildConnectionHints(DatabaseConfig config, SQLException exception, Throwable rootCause) {
        List<String> hints = new ArrayList<>();
        String message = lower(exception == null ? null : exception.getMessage());
        String rootMessage = lower(rootCause == null ? null : rootCause.getMessage());

        if (message.contains("path") || message.contains("no such file")
                || rootMessage.contains("no such file") || rootMessage.contains("cannot open")) {
            hints.add("Check that the database file path in 'database' or 'jdbcUrl' is correct and readable");
            hints.add("SQLite requires the containing directory to already exist");
        }
        if (message.contains("locked") || rootMessage.contains("locked") || rootMessage.contains("busy")) {
            hints.add("Database file is locked by another process; SQLite only allows one writer at a time");
        }
        if (message.contains("readonly") || rootMessage.contains("readonly")
                || message.contains("permission") || rootMessage.contains("permission")) {
            hints.add("Check file system permissions on the database file and its directory");
        }

        List<String> parentHints = super.buildConnectionHints(config, exception, rootCause);
        for (String hint : parentHints) {
            if (!hints.contains(hint)) {
                hints.add(hint);
            }
        }
        return hints;
    }

    @Override
    public Map<String, String> collectConnectionInfo(Connection conn, DatabaseConfig config) throws SQLException {
        Map<String, String> info = new LinkedHashMap<>(super.collectConnectionInfo(conn, config));
        putAllNonBlank(info, querySingleRow(conn, "SELECT sqlite_version() AS productVersion"));
        info.put("currentSchema", "main");
        info.put("currentDatabase", "main");
        info.put("sampleLimitSql", "SELECT * FROM \"table_name\" LIMIT 100");
        return info;
    }
}
