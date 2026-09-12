package com.sqlcli.strategy;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SqlExecutionPolicy {

    private final boolean allowStandardUpdate;
    private final boolean allowStandardDelete;
    private final boolean allowAlterMutation;
    private final boolean allowInsert;
    private final boolean allowDdl;
    private final boolean allowShow;
    private final boolean allowMultipleStatements;
    private final boolean generateRecoverySql;
    /**
     * 只读守卫默认开启：这是安全控制，漏写必须落到「更严格」的一侧。
     * 原本 GuardStage 里是无条件校验，参数化之后不加 Builder.Default 就变成
     * 「新方言忘了写这一行 → readonly 别名可以写库」，方向正好反了。
     */
    @Builder.Default
    private final boolean requireReadonlyGuard = true;
    private final String unsupportedUpdateMessage;
    private final String unsupportedDeleteMessage;

    /** MySQL / Oracle / PostgreSQL 走完全一样的标准 RDBMS 执行策略，三家共用这一份。 */
    public static final SqlExecutionPolicy STANDARD_RDBMS_POLICY = SqlExecutionPolicy.builder()
            .allowStandardUpdate(true)
            .allowStandardDelete(true)
            .allowAlterMutation(true)
            .allowInsert(true)
            .allowDdl(true)
            .allowShow(true)
            .allowMultipleStatements(false)
            .generateRecoverySql(true)
            .requireReadonlyGuard(true)
            .unsupportedUpdateMessage(null)
            .unsupportedDeleteMessage(null)
            .build();

    public static final SqlExecutionPolicy CLICKHOUSE_POLICY = SqlExecutionPolicy.builder()
            .allowStandardUpdate(false)
            .allowStandardDelete(false)
            .allowAlterMutation(true)
            .allowInsert(true)
            .allowDdl(true)
            .allowShow(true)
            .allowMultipleStatements(false)
            .generateRecoverySql(false)
            .requireReadonlyGuard(true)
            .unsupportedUpdateMessage("ClickHouse 不支持标准 UPDATE，且无事务语义，当前恢复 SQL 机制不适用。"
                    + "如确需变更数据，请使用 ALTER TABLE ... UPDATE（mutation，异步执行，不生成恢复 SQL）。")
            .unsupportedDeleteMessage("ClickHouse 不支持标准 DELETE，且无事务语义，当前恢复 SQL 机制不适用。"
                    + "如确需删除数据，请使用 ALTER TABLE ... DELETE（mutation，异步执行，不生成恢复 SQL）。")
            .build();
}
