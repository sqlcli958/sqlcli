package com.sqlcli.profile;

import com.sqlcli.strategy.DatabaseStrategies;
import com.sqlcli.strategy.DatabaseStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 单列数据剖析引擎——外延侧第一块砖。
 *
 * <p>产出行数 / 空值率 / distinct 数 / 最值 / 长度分布，低基数列额外给出实际取值候选。
 * 只做单列这一层，多列（同表函数依赖）与跨表（包含依赖）都依赖这一层的结果剪枝，
 * 这次不做，见任务背景。
 *
 * <p><b>方言不写死</b>：标识符引用与 SELECT ... LIMIT n 分别复用
 * {@link DatabaseStrategy#quoteIdentifier}/{@link DatabaseStrategy#qualifyTableName}
 * 和本包的 {@link SamplingDialect}，不在这个类里出现任何一家数据库的专属语法字符串。
 *
 * <p><b>单列失败不拖垮整表</b>：每一列独立 try/catch，失败只把该列标记为
 * {@code skipped} 并记录原因，循环继续。这里有一个容易漏掉的坑：PostgreSQL
 * 在非 autocommit 模式下，一条语句报错会把整个事务标记为 aborted，
 * 后续所有查询不先 {@code ROLLBACK} 就会连锁失败——不是防御性编程，是真实会发生的坑。
 * 因此每个 catch 分支都会尝试 {@link #rollbackIfNeeded}，为下一列/下一步查询清场。
 *
 * <p><b>采样一致性的已知局限</b>：每一列各自起一条 {@code SELECT col FROM table LIMIT n}
 * 采样查询，不保证不同列采到的是同一批物理行（多数数据库在没有 ORDER BY 时不保证
 * LIMIT 的行选择稳定）。这不影响单列统计本身的正确性，但意味着"跨列"的推断
 * （第 2、3 层：包含依赖、函数依赖）不能直接复用这里各列各自的采样结果，
 * 需要自己起一次带一致行集合的采样查询——留给下一批。
 */
public final class TableProfiler {

    private static final Logger log = LoggerFactory.getLogger(TableProfiler.class);

    /** 采样子查询的别名，固定的 sqlcli_ 前缀避免和业务列/表同名冲突。 */
    private static final String SAMPLE_ALIAS = "sqlcli_profile_sample";

    private TableProfiler() {
    }

    /**
     * 对一张表的若干列跑一次剖析。
     *
     * @param connection 调用方负责生命周期（打开/关闭/事务边界），本方法不 close 它；
     *                   这与 {@code DatabaseStrategy} 系列方法的约定一致
     * @param dbType     方言标识，取值同 {@code DatabaseConfig.getType()}（mysql/oracle/
     *                   postgresql/clickhouse），未识别的类型走 generic 兜底
     * @param columns    要剖析的列；单列失败只影响自己，不影响其余列
     */
    public static TableProfile profile(Connection connection, String dbType, String schemaName, String tableName,
                                        List<ColumnSpec> columns, ProfileOptions options) {
        DatabaseStrategy strategy = DatabaseStrategies.resolve(dbType);
        Boolean originalReadOnly = applyReadOnlyHint(connection, options);
        try {
            long rowCount = -1;
            String rowCountError = null;
            try {
                rowCount = countRows(connection, strategy, schemaName, tableName, options.queryTimeoutSeconds());
            } catch (SQLException e) {
                rowCountError = describe(e);
                rollbackIfNeeded(connection);
                log.debug("表行数统计失败 {}.{}: {}", schemaName, tableName, rowCountError);
            }

            List<ColumnProfile> results = new ArrayList<>();
            for (ColumnSpec spec : columns) {
                results.add(profileColumnSafely(connection, strategy, dbType, schemaName, tableName, spec, options));
            }

            return new TableProfile(schemaName, tableName, rowCount, rowCountError, Instant.now(), results);
        } finally {
            restoreReadOnly(connection, originalReadOnly);
        }
    }

    private static ColumnProfile profileColumnSafely(Connection connection, DatabaseStrategy strategy, String dbType,
                                                       String schema, String table, ColumnSpec spec,
                                                       ProfileOptions options) {
        try {
            return profileColumn(connection, strategy, dbType, schema, table, spec, options);
        } catch (Exception e) {
            // catch Exception 而不是只 catch SQLException：部分 JDBC 驱动对不支持的类型转换
            // 抛的是运行时异常（例如老驱动对某些 LOB 类型调 getString 会抛
            // UnsupportedOperationException），受检异常之外的失败同样不能拖垮整表。
            rollbackIfNeeded(connection);
            String reason = describe(e);
            log.debug("列剖析失败 {}.{}.{}: {}", schema, table, spec.columnName(), reason);
            return ColumnProfile.skipped(spec.columnName(), reason);
        }
    }

    private static ColumnProfile profileColumn(Connection connection, DatabaseStrategy strategy, String dbType,
                                                 String schema, String table, ColumnSpec spec, ProfileOptions options)
            throws SQLException {
        String quotedCol = strategy.quoteIdentifier(spec.columnName());
        String sampledFrom = sampledFrom(strategy, dbType, schema, table, quotedCol, options.sampleSize());

        String aggregateSql = aggregateSql(quotedCol, sampledFrom);

        long total;
        long nonNull;
        long distinct;
        String minValue;
        String maxValue;
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(options.queryTimeoutSeconds());
            try (ResultSet rs = st.executeQuery(aggregateSql)) {
                if (!rs.next()) {
                    return ColumnProfile.skipped(spec.columnName(), "聚合查询未返回行");
                }
                total = rs.getLong(1);
                nonNull = rs.getLong(2);
                distinct = rs.getLong(3);
                minValue = rs.getString(4);
                maxValue = rs.getString(5);
            }
        }

        double nullRatio = total > 0 ? (double) (total - nonNull) / total : 0.0;
        boolean lowCardinality = distinct > 0 && distinct <= options.lowCardinalityThreshold();

        List<String> enumValues = List.of();
        if (lowCardinality && !spec.sensitive()) {
            enumValues = fetchEnumValues(connection, strategy, dbType, schema, table, quotedCol, spec, options);
        }

        ColumnProfile.LengthStats lengthStats = null;
        TopValue top = new TopValue(null, 0);
        if (nonNull > 0) {
            lengthStats = fetchLengthStats(connection, quotedCol, sampledFrom, options.queryTimeoutSeconds());
            top = fetchTopValue(connection, dbType, quotedCol, sampledFrom, spec, options.queryTimeoutSeconds());
        }

        return new ColumnProfile(spec.columnName(), false, null, total, nonNull, nullRatio, distinct,
                lowCardinality, enumValues, minValue, maxValue, lengthStats, top.value(), top.count());
    }

    /** package-private：{@code schema data-quality} 连库前要把将要执行的查询原样打给用户看。 */
    static String aggregateSql(String quotedCol, String sampledFrom) {
        return "SELECT COUNT(*), COUNT(" + quotedCol + "), COUNT(DISTINCT " + quotedCol + "), "
                + "MIN(" + quotedCol + "), MAX(" + quotedCol + ") FROM " + sampledFrom;
    }

    /** 同上，取值分布的头一名。 */
    static String topValueSql(String dbType, String quotedCol, String sampledFrom) {
        return "SELECT " + quotedCol + ", COUNT(*) FROM " + sampledFrom
                + " WHERE " + quotedCol + " IS NOT NULL GROUP BY " + quotedCol
                + " ORDER BY COUNT(*) DESC" + SamplingDialect.limitClause(dbType, 1);
    }

    record TopValue(String value, long count) {
    }

    /**
     * 出现次数最多的那个非空取值和它的次数——「99% 的值都是同一个」这条判据的唯一输入。
     *
     * <p>只取头一名而不是整份直方图：判「分布是不是压在一个值上」只需要头一名的占比，
     * 完整分布是另一个还不存在的消费方的需求。
     *
     * <p>敏感列照样统计次数、但不带回取值——与 {@link #fetchEnumValues} 同一条约束，
     * 而且这里更要紧：占比 99% 的那个值往往正是最有辨识度的那个。
     */
    private static TopValue fetchTopValue(Connection connection, String dbType, String quotedCol,
                                            String sampledFrom, ColumnSpec spec, int timeoutSeconds) {
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = st.executeQuery(topValueSql(dbType, quotedCol, sampledFrom))) {
                if (!rs.next()) {
                    return new TopValue(null, 0);
                }
                return new TopValue(spec.sensitive() ? null : rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException e) {
            rollbackIfNeeded(connection);
            log.debug("取值分布统计失败 {}: {}", spec.columnName(), e.getMessage());
            return new TopValue(null, 0);
        }
    }

    /**
     * 低基数列的实际取值候选——这是 {@code enumValues} 自动候选的直接来源。
     * 查不出来不算列失败：聚合查询里的 distinct 数已经是可信结果，取值列表只是锦上添花，
     * 拿不到就给空列表，不影响该列其余统计量。
     */
    private static List<String> fetchEnumValues(Connection connection, DatabaseStrategy strategy, String dbType,
                                                  String schema, String table, String quotedCol, ColumnSpec spec,
                                                  ProfileOptions options) {
        String sampledFrom = sampledFrom(strategy, dbType, schema, table, quotedCol, options.sampleSize());
        String sql = "SELECT DISTINCT " + quotedCol + " FROM " + sampledFrom + " WHERE " + quotedCol + " IS NOT NULL";
        List<String> values = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(options.queryTimeoutSeconds());
            try (ResultSet rs = st.executeQuery(sql)) {
                // 阈值在聚合查询里已经把关过一次；这里再截断一次是双重保险，
                // 防止两次查询之间数据发生变化（并发写入）导致取值列表意外变长。
                while (rs.next() && values.size() < options.lowCardinalityThreshold()) {
                    values.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            rollbackIfNeeded(connection);
            log.debug("枚举候选取值失败 {}.{}.{}: {}", schema, table, spec.columnName(), e.getMessage());
            return List.of();
        }
        return values;
    }

    /**
     * 字符串列的长度分布。用 {@code LENGTH()} 直接探测而不是先判断列的 JDBC 类型再决定
     * 要不要跑这条查询：类型到"能不能算长度"的映射本身就是方言相关的（同一个 JDBC 类型码
     * 在不同驱动上是否支持 LENGTH() 不一致），让数据库自己说支持不支持，比维护一张
     * 类型映射表更不容易漏判，代价是失败时才知道——但失败只丢一项统计，不丢整列。
     */
    private static ColumnProfile.LengthStats fetchLengthStats(Connection connection, String quotedCol,
                                                                String sampledFrom, int timeoutSeconds) {
        String sql = "SELECT MIN(LENGTH(" + quotedCol + ")), MAX(LENGTH(" + quotedCol + ")), "
                + "AVG(LENGTH(" + quotedCol + ")) FROM " + sampledFrom + " WHERE " + quotedCol + " IS NOT NULL";
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = st.executeQuery(sql)) {
                if (!rs.next()) {
                    return null;
                }
                return new ColumnProfile.LengthStats(rs.getInt(1), rs.getInt(2), rs.getDouble(3));
            }
        } catch (SQLException e) {
            rollbackIfNeeded(connection);
            log.debug("长度统计失败: {}", e.getMessage());
            return null;
        }
    }

    private static long countRows(Connection connection, DatabaseStrategy strategy, String schema, String table,
                                   int timeoutSeconds) throws SQLException {
        String qualified = strategy.qualifyTableName(schema, table);
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + qualified)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** package-private：给测试直接验证方言拼接（LIMIT vs FETCH FIRST、标识符转义）而不必起真连接。 */
    static String sampledFrom(DatabaseStrategy strategy, String dbType, String schema, String table,
                               String quotedCol, int sampleSize) {
        String qualifiedTable = strategy.qualifyTableName(schema, table);
        String limitClause = SamplingDialect.limitClause(dbType, sampleSize);
        return "(SELECT " + quotedCol + " FROM " + qualifiedTable + limitClause + ") " + SAMPLE_ALIAS;
    }

    private static Boolean applyReadOnlyHint(Connection connection, ProfileOptions options) {
        if (!options.readOnlyConnection()) {
            return null;
        }
        try {
            boolean original = connection.isReadOnly();
            connection.setReadOnly(true);
            return original;
        } catch (SQLException e) {
            log.debug("connection.setReadOnly 不受支持，忽略: {}", e.getMessage());
            return null;
        }
    }

    private static void restoreReadOnly(Connection connection, Boolean original) {
        if (original == null) {
            return;
        }
        try {
            connection.setReadOnly(original);
        } catch (SQLException ignored) {
            // 恢复失败不影响剖析结果本身，忽略即可。
        }
    }

    /** package-private：{@code DataQualityProbes} 自己起的那几条查询也要靠它给下一条清场。 */
    static void rollbackIfNeeded(Connection connection) {
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // 见类头注释：回滚是为了让下一条查询能正常跑，回滚本身失败就没有更好的处理方式，
            // 下一条查询大概率还是会失败，那时候会再被它自己的 catch 记成一次跳过。
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }
}
