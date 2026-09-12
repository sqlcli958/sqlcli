/**
 * 血缘四类的展示文案。枚举值本身（identity / transformation / aggregation / rule）
 * 是 CLI 与 API 的契约，照 CLAUDE.md 直接显示英文原值；括号里那半句说的是
 * 「拿这个值时该怎么办」，跟 CLI 文本输出同一份措辞。
 */
export type LineageKind = 'identity' | 'transformation' | 'aggregation' | 'rule';

const LABELS: Record<LineageKind, string> = {
  identity: 'identity · 快照',
  transformation: 'transformation · 同行计算',
  aggregation: 'aggregation · 多行汇总',
  rule: 'rule · 规则写入',
};

const HINTS: Record<LineageKind, string> = {
  identity: '原样抄来的快照，可能过期；要当前值去源列',
  transformation: '同一行按公式算出；用 expression，别猜',
  aggregation: '多行汇总，可能与明细漂移；核对时从明细重算',
  rule: '值不来自任何列，源列只决定写不写；改源列前先看 through',
};

export function lineageKindLabel(kind?: string | null): string {
  return kind && kind in LABELS ? LABELS[kind as LineageKind] : '未分类';
}

export function lineageKindHint(kind?: string | null): string {
  return kind && kind in HINTS ? HINTS[kind as LineageKind] : '旧记录，没有类别；重跑 add-lineage --kind 补上';
}

/** schema.table → table：整张图都在一个 schema 里，前缀只占地方。 */
export function tableOnly(schemaTable: string): string {
  const dot = schemaTable.indexOf('.');
  return dot < 0 ? schemaTable : schemaTable.slice(dot + 1);
}

/** schema.table.column → table.column：详情里整栏都是一个 schema，前缀只会把标题挤成两行。 */
export function shortRef(columnRef: string): string {
  const parts = columnRef.split('.');
  return parts.length >= 3 ? parts.slice(-2).join('.') : columnRef;
}

/** 四类各一个色调，边、标签、图例共用一份——同一类在三处必须是同一个颜色。 */
export const KIND_COLORS: Record<LineageKind, string> = {
  identity: '#64748b',
  transformation: '#2563eb',
  // 蓝紫太近，多行汇总换成绿：跟蓝（同行计算）、琥珀（规则）、灰（快照）四个色相都拉开
  aggregation: '#059669',
  rule: '#d97706',
};

export const KIND_ORDER: LineageKind[] = ['identity', 'transformation', 'aggregation', 'rule'];
