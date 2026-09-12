/**
 * SQL 补全的纯逻辑：光标处要补什么、候选怎么排。不碰 DOM，好测。
 *
 * 参照 IDEA 的行为，但只做投入产出比高的那几条：
 *   1. 认限定符——`qm_pct.` 补这个 schema 下的表，`o.` 补别名 o 那张表的列
 *   2. 认上下文——FROM/JOIN 后面补表，SELECT/WHERE/ON 后面补列
 *   3. 排序——前缀 > 词边界 > 子序列，同档短的在前
 * 不做的：关键字补全（一个 textarea 里补 SELECT 是噪声）、参数提示、live template。
 */

export type CompletionKind = 'table' | 'column';

export interface CompletionItem {
  /** 插入的文本 */
  text: string;
  /** 列表里显示的主文本 */
  label: string;
  /** 右侧的次要信息：表注释、列类型与业务名 */
  detail?: string;
  kind: CompletionKind;
}

export interface CompletionContext {
  /** 限定符：`qm_pct.orders` 里的 `qm_pct`、`o.id` 里的 `o`；没有就是 null */
  qualifier: string | null;
  /** 限定符之后已经输入的部分，可能是空串（刚打完点） */
  prefix: string;
  /** 要被替换掉的区间（含限定符） */
  start: number;
  end: number;
  /** 这个位置期待什么 */
  expect: 'table' | 'column' | 'any';
}

/** 语句里引用到的表及其别名。 */
export interface TableRef {
  table: string;
  alias: string | null;
}

const TABLE_KEYWORDS = new Set(['FROM', 'JOIN', 'INTO', 'UPDATE', 'TABLE']);
const COLUMN_KEYWORDS = new Set([
  'SELECT', 'WHERE', 'ON', 'AND', 'OR', 'SET', 'BY', 'HAVING', 'VALUES', 'NOT', 'IN',
]);

/**
 * 光标处的补全上下文；不该补全时返回 null。
 *
 * 「点后面什么都没打」也要补（`o.` → 列全表），所以不能像原来那样看到点就放弃——
 * 那正是最需要提示的时刻。
 */
export function readContext(text: string, cursor: number): CompletionContext | null {
  const head = text.slice(0, cursor);
  // 字符串字面量里不补：`WHERE name = 'ord` 补出表名只会碍事
  if (insideStringLiteral(head)) return null;

  const token = /[\w$.]*$/.exec(head)?.[0] ?? '';
  const start = cursor - token.length;
  const dot = token.lastIndexOf('.');
  const qualifier = dot >= 0 ? token.slice(0, dot) : null;
  const prefix = dot >= 0 ? token.slice(dot + 1) : token;

  // 既没输入任何东西也没有限定符 → 不主动弹（要弹按 Ctrl+Space，那条路径直接给 expect）
  if (!token) return null;

  return { qualifier, prefix, start, end: cursor, expect: expectationAt(head, start) };
}

/** 手动触发（Ctrl+Space）：即使光标前是空白也给上下文。 */
export function readContextForced(text: string, cursor: number): CompletionContext {
  return readContext(text, cursor) ?? {
    qualifier: null,
    prefix: '',
    start: cursor,
    end: cursor,
    expect: expectationAt(text.slice(0, cursor), cursor),
  };
}

/** 看光标前最近的那个关键字决定补什么。 */
function expectationAt(head: string, tokenStart: number): CompletionContext['expect'] {
  const before = head.slice(0, tokenStart);
  const words = before.toUpperCase().match(/[A-Z_]+/g);
  if (!words) return 'any';
  for (let i = words.length - 1; i >= 0; i--) {
    if (TABLE_KEYWORDS.has(words[i])) return 'table';
    if (COLUMN_KEYWORDS.has(words[i])) return 'column';
  }
  return 'any';
}

/** 单引号成对算闭合；`''` 是转义，正好被「下一个引号翻转状态」处理掉。 */
function insideStringLiteral(head: string): boolean {
  let inside = false;
  for (const ch of head) if (ch === "'") inside = !inside;
  return inside;
}

/**
 * 语句里引用了哪些表，以及它们的别名。
 *
 * 只认 `FROM/JOIN/UPDATE/INTO <表> [AS] [别名]` 这一种写法——子查询、CTE、逗号连接
 * 都不认。补全给错候选的代价只是多按一次方向键，为它上一个 SQL parser 不划算。
 */
export function collectTableRefs(sql: string): TableRef[] {
  const refs: TableRef[] = [];
  const pattern = /\b(?:from|join|update|into)\s+([\w$.]+)(?:\s+(?:as\s+)?([a-z_][\w$]*))?/gi;
  const RESERVED = new Set(['where', 'join', 'on', 'set', 'left', 'right', 'inner', 'outer',
    'group', 'order', 'limit', 'having', 'values', 'select', 'and', 'or', 'using', 'cross']);
  for (const match of sql.matchAll(pattern)) {
    const alias = match[2] && !RESERVED.has(match[2].toLowerCase()) ? match[2] : null;
    refs.push({ table: match[1], alias });
  }
  return refs;
}

/** 限定符指向哪张表：先当别名找，再当表名/schema 找。 */
export function resolveQualifier(qualifier: string, refs: TableRef[]): string | null {
  const lower = qualifier.toLowerCase();
  const byAlias = refs.find((ref) => ref.alias?.toLowerCase() === lower);
  if (byAlias) return byAlias.table;
  const byName = refs.find((ref) => {
    const name = ref.table.toLowerCase();
    return name === lower || name.endsWith(`.${lower}`);
  });
  return byName ? byName.table : null;
}

/**
 * 排序：前缀命中 > 词边界命中 > 子序列命中，同档短的在前。
 *
 * 子序列那档是「um → user_menu」这种打首字母的用法，IDEA 里最顺手的一条；
 * 但它匹配得太宽，所以必须排在前两档后面，否则精确前缀会被淹没。
 */
export function rank(items: CompletionItem[], query: string, limit = 12): CompletionItem[] {
  if (!query) return items.slice(0, limit);
  const q = query.toLowerCase();
  const scored: { item: CompletionItem; score: number }[] = [];
  for (const item of items) {
    const name = (item.text.split('.').pop() ?? item.text).toLowerCase();
    const full = item.text.toLowerCase();
    let score: number;
    if (name.startsWith(q)) score = 0;
    else if (full.startsWith(q)) score = 1;
    else if (wordBoundaryHit(name, q)) score = 2;
    else if (full.includes(q)) score = 3;
    else if (isSubsequence(q, name)) score = 4;
    else continue;
    scored.push({ item, score });
  }
  scored.sort((a, b) => a.score - b.score || a.item.text.length - b.item.text.length
    || a.item.text.localeCompare(b.item.text));
  return scored.slice(0, limit).map((entry) => entry.item);
}

/** `menu` 命中 `sys_menu`：下划线后面也算词首。 */
function wordBoundaryHit(name: string, query: string): boolean {
  return name.split(/[_\-.]/).some((part) => part.startsWith(query));
}

function isSubsequence(query: string, name: string): boolean {
  let i = 0;
  for (const ch of name) {
    if (ch === query[i]) i++;
    if (i === query.length) return true;
  }
  return query.length === 0;
}
