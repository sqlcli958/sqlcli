package com.sqlcli.task;

import com.sqlcli.yearning.YearningQueryExecutor;
import com.sqlcli.yearning.YearningQueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Yearning 通道：HTTP，不是 JDBC，没有 Statement、没有事务、不支持写。
 * 写语句已经在 {@link GuardStage} 被挡掉，这里只处理只读查询。
 *
 * <p>maxRows 只能在拿到全部结果后本地截断——Yearning 服务端可能已经先截断过一次，
 * 我们的 {@code truncated} 只反映本地上限，感知不到服务端那一层。
 */
final class YearningBackend implements SqlBackend {

    private final YearningQueryExecutor executor;

    YearningBackend(YearningQueryExecutor executor) {
        this.executor = executor;
    }

    @Override
    public boolean supportsWrites() {
        return false;
    }

    @Override
    public void execute(SqlTaskContext ctx) {
        YearningQueryResult result = executor.query(ctx.config, ctx.sql);
        List<String> names = result.getColumns();
        ctx.resultColumns = names.stream().map(n -> new SqlTaskResult.Column(n, null)).toList();

        List<Map<String, Object>> rawRows = result.getRows();
        int limit = ctx.limits.maxRows();
        boolean truncated = limit > 0 && rawRows.size() > limit;
        List<Map<String, Object>> effective = truncated ? rawRows.subList(0, limit) : rawRows;

        List<List<Object>> rows = new ArrayList<>(effective.size());
        for (Map<String, Object> row : effective) {
            List<Object> values = new ArrayList<>(names.size());
            for (String name : names) {
                values.add(row.get(name));
            }
            rows.add(values);
        }
        ctx.resultRows = rows;
        ctx.resultTruncated = truncated;
    }
}
