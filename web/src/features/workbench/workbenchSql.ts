import type { SqlColumnDto, SqlCellValue } from '../../types/api';

/**
 * 写语句关键字。**只用来决定按钮文案和走不走确认面板**——真正的强制在服务端，
 * 判错了最坏的结果是文案不准，不会让一条写语句绕过 readonly、审批或 WHERE 校验。
 */
const WRITE_KEYWORDS = new Set([
  'INSERT', 'UPDATE', 'DELETE', 'MERGE',
  'CREATE', 'ALTER', 'DROP', 'TRUNCATE', 'GRANT', 'REVOKE',
]);

/** 取首个关键字。行注释、块注释和前导空白都跳过，否则 `-- x\nDELETE ...` 会被当成只读。 */
export function firstKeyword(sql: string): string {
  let rest = sql;
  for (;;) {
    const trimmed = rest.replace(/^\s+/, '');
    if (trimmed.startsWith('--')) {
      const end = trimmed.indexOf('\n');
      if (end < 0) return '';
      rest = trimmed.slice(end + 1);
      continue;
    }
    if (trimmed.startsWith('/*')) {
      const end = trimmed.indexOf('*/');
      if (end < 0) return '';
      rest = trimmed.slice(end + 2);
      continue;
    }
    return (trimmed.match(/^[A-Za-z]+/)?.[0] ?? '').toUpperCase();
  }
}

export function isWriteStatement(sql: string): boolean {
  return WRITE_KEYWORDS.has(firstKeyword(sql));
}

/** 一个 CSV 字段：含分隔符、引号或换行时加引号，内部引号翻倍。NULL 输出空字段。 */
function csvField(value: SqlCellValue): string {
  if (value === null || value === undefined) return '';
  const text = String(value);
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/**
 * 从结构化结果生成 CSV。
 *
 * 前置 BOM 是给 Excel 的：没有它，Excel 会按系统 ANSI 代码页解析，中文全是乱码。
 * 行分隔用 CRLF（RFC 4180）。
 */
export function buildCsv(columns: SqlColumnDto[], rows: SqlCellValue[][]): string {
  const lines = [columns.map((c) => csvField(c.name)).join(',')];
  for (const row of rows) lines.push(row.map(csvField).join(','));
  return `\uFEFF${lines.join('\r\n')}\r\n`;
}

/** 触发浏览器下载。 */
export function downloadCsv(fileName: string, content: string): void {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

/**
 * 跨页把一条 SQL 送进工作台编辑器。
 *
 * 走 sessionStorage 而不是查询参数：`SELECT` 语句动辄几百字符，塞进 URL 又长又会被
 * 转义得没法读，刷新一次还会重新填一遍。取一次就清掉。
 */
const PENDING_SQL_KEY = 'sql-cli-workbench-pending-sql';

export function stashWorkbenchSql(sql: string): void {
  sessionStorage.setItem(PENDING_SQL_KEY, sql);
}

export function takeWorkbenchSql(): string | null {
  const sql = sessionStorage.getItem(PENDING_SQL_KEY);
  if (sql !== null) sessionStorage.removeItem(PENDING_SQL_KEY);
  return sql;
}

/** 表详情跳工作台时带的默认语句。 */
export function selectSampleSql(schema: string, table: string): string {
  return `SELECT * FROM ${schema}.${table} LIMIT 100`;
}
