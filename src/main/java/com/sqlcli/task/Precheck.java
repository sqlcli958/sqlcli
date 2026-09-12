package com.sqlcli.task;

import java.util.List;

/**
 * 写操作（UPDATE/DELETE）在执行前收集的信息，给审批人和 dry-run 调用方看。
 *
 * <p>这是参考值，不是承诺：批准可能发生在提交之后几分钟，数据已经变了。真正的保护
 * 在执行时——{@code JdbcBackend} 同连接重读原始行生成恢复 SQL，那一步永远是准的。
 */
public record Precheck(
        String table,
        boolean recoverySupported,
        List<String> primaryKey,
        Long estimatedRows,
        String skippedReason
) {
    static Precheck skipped(String table, String reason) {
        return new Precheck(table, false, List.of(), null, reason);
    }
}
