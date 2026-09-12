package com.sqlcli.task;

/**
 * 一条传输通道的执行实现。不同后端不是链上的 {@code if}——Yearning 走 HTTP、
 * 没有 Statement、没有事务、不支持写，把它塞进线性 Stage 链会让整条链都带着这个分支。
 */
interface SqlBackend {

    /** 不支持写的后端，写语句在 GuardStage 就地拒绝——比执行时才拒绝早，且信息一致。 */
    boolean supportsWrites();

    /** 执行并把结果填进 {@code ctx.result}（columns / rows / truncated / affectedRows / recoveryPath）。 */
    void execute(SqlTaskContext ctx) throws Exception;
}
