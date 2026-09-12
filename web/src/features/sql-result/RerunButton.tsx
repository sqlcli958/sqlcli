import { useMutation } from '@tanstack/react-query';
import { executeSql } from '../../api/sqlExecute';
import { Button } from '../../ui/Button';
import type { SqlExecutionRecordDto } from '../../types/api';
import { ResultTable } from './ResultTable';

/**
 * 一条执行记录的「执行」按钮 + 结果面板。返回 fragment，宿主行自己决定布局。
 *
 * 能否重放取决于有没有原文：新记录带 rawSql，旧记录只有脱敏文本（字面量被换成
 * '<redacted>'），后者按不了。
 */
export function RerunButton({ record }: { record: SqlExecutionRecordDto }) {
  const sql = record.rawSql ?? record.sql;
  const replayable = record.rawSqlAvailable ?? !sql.includes('<redacted>');
  const run = useMutation({ mutationFn: () => executeSql(sql) });

  return (
    <>
      <Button
        size="sm"
        disabled={!replayable || run.isPending}
        title={replayable ? '重新执行这条 SQL' : '历史未保存原文，无法重放'}
        onClick={() => run.mutate()}
      >
        {run.isPending ? '执行中…' : '执行'}
      </Button>
      {(run.isError || run.isSuccess) && (
        <div className="rerun-panel">
          {run.isError && <p className="rerun-error" role="alert">执行失败：{run.error.message}</p>}
          {run.data && <ResultTable result={run.data} />}
        </div>
      )}
    </>
  );
}
