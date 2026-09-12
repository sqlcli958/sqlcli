package com.sqlcli.metric;

import java.util.Locale;

/**
 * 时间粒度截断函数按方言生成骨架，只覆盖 metric 展开需要的 day/week/month/quarter/year 五档。
 *
 * <p>不放进 {@code com.sqlcli.strategy} 的方言类：那批类（{@code MySqlDatabaseStrategy} /
 * {@code OracleDatabaseStrategy} / ...）的职责是连接、DDL、SQL 改写这类和数据库会话强绑定的
 * 行为——接口里几乎每个方法都要 {@code Connection}。时间截断只是纯字符串拼接，不需要连接，
 * 硬塞进那个接口只会把它的职责边界搅浑。这里按方言的 {@code DatabaseStrategy#type()} 单独
 * 建一张只读的查表，消费方（{@link MetricSqlExpander}）只读 {@code DatabaseStrategy}，
 * 不改它。
 *
 * <p>只支持已接入的四个方言（mysql/oracle/postgresql/clickhouse）和五档粒度；
 * 命中不了就明确报错，不悄悄退化成某个方言的语法在别的库上跑不通。
 */
public final class GrainSqlDialect {
    /** 能展开时间粒度的方言。新增方言时这里和 {@link #truncate} 的 switch 要一起加。 */
    private static final java.util.Set<String> SUPPORTED =
            java.util.Set.of("mysql", "oracle", "postgresql", "clickhouse", "sqlite");

    private GrainSqlDialect() {
    }

    /**
     * 这个方言能不能展开时间粒度。
     *
     * <p>暴露出来是为了让 {@code schema add-metric} 在**定义指标的时候**就能提醒
     * 「这个库展不开 grains」，而不是等到 {@code expand-metric} 才炸。
     * 定义指标的人才是能决定怎么办的人（换库、改口径、或者接受只能不带粒度展开）；
     * 等到消费时才发现，那个人往往不知道该找谁。
     */
    public static boolean supportsDialect(String dbType) {
        return SUPPORTED.contains(dbType == null ? "" : dbType.trim().toLowerCase(Locale.ROOT));
    }

    static String truncate(String dbType, String columnRef, String grain) {
        String normalizedGrain = grain == null ? "" : grain.trim().toLowerCase(Locale.ROOT);
        String type = dbType == null ? "" : dbType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "mysql" -> mysql(columnRef, normalizedGrain);
            case "oracle" -> oracle(columnRef, normalizedGrain);
            case "postgresql" -> postgres(columnRef, normalizedGrain);
            case "clickhouse" -> clickhouse(columnRef, normalizedGrain);
            case "sqlite" -> sqlite(columnRef, normalizedGrain);
            // 明确报错而不是猜一个语法：猜错的截断函数会生成一条能跑但分桶错误的 SQL，
            // 那种错不报警、有结果、数是错的。宁可在这里挡住，也不要让它流到报表里。
            default -> throw new MetricExpansionException(
                    "时间粒度展开暂不支持数据库类型 '" + dbType + "'（当前支持 "
                            + String.join("/", new java.util.TreeSet<>(SUPPORTED)) + "）。"
                            + "指标定义本身没问题：去掉 --grain 可以正常展开，只是不按时间分桶。"
                            + "要支持这个库，需要在 GrainSqlDialect 里补它的截断函数。");
        };
    }

    private static String mysql(String col, String grain) {
        return switch (grain) {
            case "day" -> "DATE(" + col + ")";
            // %x-%v 是 ISO 周（周一开始、跨年边界正确），比 %Y-%u 更适合做分桶键。
            case "week" -> "DATE_FORMAT(" + col + ", '%x-%v')";
            case "month" -> "DATE_FORMAT(" + col + ", '%Y-%m')";
            case "quarter" -> "CONCAT(YEAR(" + col + "), '-Q', QUARTER(" + col + "))";
            case "year" -> "YEAR(" + col + ")";
            default -> throw unsupportedGrain("mysql", grain);
        };
    }

    private static String oracle(String col, String grain) {
        return switch (grain) {
            case "day" -> "TRUNC(" + col + ", 'DD')";
            case "week" -> "TRUNC(" + col + ", 'IW')";
            case "month" -> "TRUNC(" + col + ", 'MM')";
            case "quarter" -> "TRUNC(" + col + ", 'Q')";
            case "year" -> "TRUNC(" + col + ", 'YYYY')";
            default -> throw unsupportedGrain("oracle", grain);
        };
    }

    private static String postgres(String col, String grain) {
        return switch (grain) {
            case "day", "week", "month", "quarter", "year" -> "DATE_TRUNC('" + grain + "', " + col + ")";
            default -> throw unsupportedGrain("postgresql", grain);
        };
    }

    private static String clickhouse(String col, String grain) {
        return switch (grain) {
            case "day" -> "toDate(" + col + ")";
            case "week" -> "toMonday(" + col + ")";
            case "month" -> "toStartOfMonth(" + col + ")";
            case "quarter" -> "toStartOfQuarter(" + col + ")";
            case "year" -> "toStartOfYear(" + col + ")";
            default -> throw unsupportedGrain("clickhouse", grain);
        };
    }

    /**
     * SQLite 没有 DATE_TRUNC/TRUNC 这类截断函数，全靠 {@code date()} 的 modifier 语法拼。
     * {@code 'start of month'}/{@code 'start of year'} 是内置 modifier，直接能用；
     * week 和 quarter 没有对应 modifier，只能用 {@code strftime('%w', ...)}（星期几，
     * 0=周日）和 {@code strftime('%m', ...)}（月份）手算偏移量。
     */
    private static String sqlite(String col, String grain) {
        return switch (grain) {
            case "day" -> "date(" + col + ")";
            // 周一为一周起点：距周一的天数 = (weekday(0=周日..6=周六) + 6) % 7。
            case "week" -> "date(" + col + ", '-' || ((strftime('%w', " + col + ") + 6) % 7) || ' days')";
            case "month" -> "date(" + col + ", 'start of month')";
            // 季度起始月 = 当月减去 (month-1)%3 个月，再退到当月 1 号。
            case "quarter" -> "date(" + col + ", 'start of month', "
                    + "'-' || ((strftime('%m', " + col + ") - 1) % 3) || ' months')";
            case "year" -> "date(" + col + ", 'start of year')";
            default -> throw unsupportedGrain("sqlite", grain);
        };
    }

    private static MetricExpansionException unsupportedGrain(String dialect, String grain) {
        return new MetricExpansionException(
                "粒度 '" + grain + "' 在 " + dialect + " 方言下不支持展开（支持 day/week/month/quarter/year）");
    }
}
