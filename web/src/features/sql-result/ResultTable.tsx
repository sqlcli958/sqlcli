import type { SqlCellValue, SqlExecuteResultDto } from '../../types/api';
import { buildCsv, downloadCsv } from '../workbench/workbenchSql';
import './result-table.css';
import { Button } from '../../ui/Button';

/**
 * 结构化查询结果表格。
 *
 * 与页面无关：只吃一个 result，不取数、不管别名、不管按钮。工作台和历史重放用的是同一个组件。
 *
 * `exportName` 给了才显示导出 CSV：历史重放的内嵌结果不需要它，
 * 那里是“看一眼当时跑出什么”，不是拿数据。
 */
export function ResultTable({ result, exportName }: { result: SqlExecuteResultDto; exportName?: string }) {
  if (result.columns.length === 0) {
    return <p className="rt-foot">语句执行完成，没有返回结果集。耗时 {result.elapsedMs} ms。</p>;
  }
  return (
    <div className="rt">
      {result.truncated && (
        <p className="rt-truncated" role="status">
          结果已截断，只返回了前 {result.rowCount} 行。加 WHERE 或 LIMIT 缩小范围，否则看到的不是全部。
        </p>
      )}
      <div className="rt-scroll">
        <table className="rt-table">
          <thead>
            <tr>
              {result.columns.map((column, index) => {
                const meta = result.columnMeta?.[column.name];
                // 值域和语义类型放 title，鼠标停留才出现；列头正文只加一行业务名
                const title = [
                  `${column.name} · ${column.type}`,
                  meta?.businessName,
                  meta?.semanticType && `语义类型：${meta.semanticType}`,
                  meta?.enumValues?.length ? `值域：${meta.enumValues.join('、')}` : undefined,
                ]
                  .filter(Boolean)
                  .join('\n');
                return (
                  <th key={`${column.name}-${index}`} scope="col" title={title}>
                    <span className="rt-col-name">{column.name}</span>
                    {meta?.businessName && <span className="rt-col-biz">{meta.businessName}</span>}
                    <span className="rt-col-type">{column.type}</span>
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {result.rows.map((row, rowIndex) => (
              <tr key={rowIndex}>
                {row.map((value, cellIndex) => (
                  <Cell key={cellIndex} value={value} />
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="rt-foot">
        {result.rowCount} 行 · {result.elapsedMs} ms
        {exportName && (
          <Button
            title="把当前结果存成 CSV（UTF-8 with BOM，Excel 直接能开）"
            onClick={() => downloadCsv(exportName, buildCsv(result.columns, result.rows))}
            variant="ghost"
            size="sm"
          >
            导出 CSV
          </Button>
        )}
      </p>
    </div>
  );
}

function Cell({ value }: { value: SqlCellValue }) {
  if (value === null || value === undefined) {
    return (
      <td className="rt-null">
        <em>NULL</em>
      </td>
    );
  }
  const text = String(value);
  // 截断交给 CSS（text-overflow），title 保留全文，避免为了显示再复制一份字符串。
  return (
    <td className={typeof value === 'number' ? 'rt-num' : undefined} title={text}>
      {text}
    </td>
  );
}
