package com.sqlcli.runstate;

/**
 * 一条执行记录的回滚脚本，连同执行前的原始行。
 *
 * <p>脚本存在运行库里，不落文件——升级前留在 {@code ~/.sql-cli/recovery/*.sql}
 * 的老记录已由 {@code RunStateStore} 的一次性回填搬进来，不再有第二种形态。
 */
public record RecoveryArtifactRow(
        long id,
        String rollbackSql,
        String backupText) {

    /** 有可执行的回滚脚本。空的说明当初就没生成出来，或者老文件在回填时已经丢了。 */
    public boolean hasScript() {
        return rollbackSql != null && !rollbackSql.isBlank();
    }
}
