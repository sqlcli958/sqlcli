package com.sqlcli.profile;

/**
 * 采样子查询的 LIMIT 子句，按方言给出正确写法。
 *
 * <p>只处理这一件事，不是又一份 {@code DatabaseStrategy}：{@code AbstractDatabaseStrategy}
 * 已经有 {@code applyDefaultLimit}（同样的问题：给 SQL 加行数上限），但它是 protected，
 * 且是为"给调用方任意写的 SQL 加 LIMIT"这个更难的场景设计的——那种 SQL 可能已经带
 * ORDER BY / UNION / 子查询，Oracle 分支才需要用 ROWNUM 包一层子查询来兼容。
 * 剖析引擎里的 SQL 从头到尾都是自己拼的最简单形态（单表、单列、无排序），
 * 不存在那些复杂情况，用标准语法直接拼即可，没有必要为了复用去放宽 strategy 包的访问权限
 * （放宽访问权限属于"改既有文件"，这次任务约束要求避免，除非真的绕不开）。
 */
final class SamplingDialect {

    private SamplingDialect() {
    }

    /**
     * Oracle 12c 才支持 {@code FETCH FIRST ... ROWS ONLY}；更早版本需要 ROWNUM 子查询包装。
     * 这里直接用 12c 语法而不是照抄 OracleDatabaseStrategy 的 ROWNUM 包装法，
     * 是因为本类拼的 SQL 本身已经是子查询形态，标准语法更短、更不容易拼错。
     * 如果将来需要支持 12c 以前的 Oracle，这是唯一要改的地方。
     *
     * @param limit &lt;= 0 表示不限（全表扫描），返回空字符串——调用方主动选择的风险，
     *              不是本方法默认给的。
     */
    static String limitClause(String dbType, int limit) {
        if (limit <= 0) {
            return "";
        }
        if ("oracle".equalsIgnoreCase(dbType)) {
            return " FETCH FIRST " + limit + " ROWS ONLY";
        }
        // MySQL / PostgreSQL / ClickHouse 三家现有 DatabaseStrategy 覆盖的方言都认标准 LIMIT，
        // generic（含测试用的 SQLite）同样认——不需要再分支。
        return " LIMIT " + limit;
    }
}
