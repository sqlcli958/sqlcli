import type { RelationType } from '../types/api';

/**
 * 按 schema 名生成稳定颜色。
 *
 * 必须返回 hex：sigma 的 WebGL 节点程序解析不了 `hsl(...)` 字符串，
 * 拿到就回退成黑色——整张图所有点都是黑的就是这么来的。
 */
export function getSchemaColor(schema: string): string {
  let hash = 0;
  for (let i = 0; i < schema.length; i++) {
    hash = ((hash << 5) - hash + schema.charCodeAt(i)) | 0;
  }
  return hslToHex(Math.abs(hash % 360), 0.62, 0.56);
}

function hslToHex(hue: number, saturation: number, lightness: number): string {
  const chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
  const channel = (n: number) => {
    const k = (n + hue / 30) % 12;
    const value = lightness - (chroma / 2) * Math.max(-1, Math.min(k - 3, 9 - k, 1));
    return Math.round(255 * value).toString(16).padStart(2, '0');
  };
  return `#${channel(0)}${channel(8)}${channel(4)}`;
}

/** Relation type line styles */
export const RELATION_LINE_STYLES: Record<RelationType, { dash: number[]; width: number }> = {
  foreign_key: { dash: [], width: 2 },
  join_observed: { dash: [5, 5], width: 1.5 },
  term_mapping: { dash: [2, 4], width: 1 },
};

/**
 * 关系类型配色。**唯一定义处**——`RelationTypeBadge` 也从这里取，
 * 免得图上的线和列表里的徽标是两种颜色。
 *
 * <p>三个色相要拉开：原来 foreign_key `#4a90d9` 和 join_observed `#7b68ee`
 * 都是蓝，1px 线宽下肉眼分不出来，等于没区分。蓝 / 琥珀是最经典的一对
 * 色盲友好组合，品红再拉开一档；线宽（2 / 1.5 / 1）是第二条冗余通道，
 * 只靠颜色区分的图对色觉障碍的人是不可读的。
 */
export const RELATION_COLORS: Record<RelationType, string> = {
  /** 数据库声明的外键，最硬的一类 */
  foreign_key: '#2563eb',
  /** 人和 Agent 维护的推断关联 */
  join_observed: '#d97706',
  /** 术语 → 表/字段 */
  term_mapping: '#db2777',
};

/** Get line style for relation type */
export function getRelationLineStyle(relationType: RelationType): { dash: number[]; width: number } {
  return RELATION_LINE_STYLES[relationType] || RELATION_LINE_STYLES.foreign_key;
}

/** Get color for relation type */
export function getRelationColor(relationType: RelationType): string {
  return RELATION_COLORS[relationType] || RELATION_COLORS.foreign_key;
}

/**
 * 关系相对字段的权重。
 *
 * 字段数在真实库里是 2~100，关系数只有 0~10，直接相加等于关系没有存在感；
 * 乘 6 之后一条关系约等于 6 个字段，关系多的表能明显浮出来，又不至于盖过宽表。
 */
const RELATION_WEIGHT = 6;

/**
 * 节点权重 = 字段数 + 出入关系数 × RELATION_WEIGHT。
 *
 * relationCount 来自后端的双向邻接表（GraphViewService.buildAdjacency 对
 * from/to 两侧都写），所以它已经是出入合计的相邻表数量。
 */
export function nodeWeight(columnCount: number, relationCount: number): number {
  return Math.max(columnCount, 0) + Math.max(relationCount, 0) * RELATION_WEIGHT;
}

/**
 * 把权重归一化到 [MIN, MAX]。
 *
 * 必须是归一化而不是"基准值 + 对数"——后者会让所有表都贴着上限、彼此分不出大小。
 * 取对数是因为权重跨度极大，线性缩放会让极少数大表吃掉整个区间。
 * `maxWeight` 传当前视图里的最大权重，所以同一张图内部可比，换个库也不会整体变大变小。
 */
export function calculateNodeSize(weight: number, maxWeight: number): number {
  const ratio = Math.log2(Math.max(weight, 0) + 1) / Math.log2(Math.max(maxWeight, 1) + 1);
  return NODE_SIZE.MIN + (NODE_SIZE.MAX - NODE_SIZE.MIN) * Math.min(ratio, 1);
}

/** Node size range constants */
export const NODE_SIZE = {
  // 499 张表的库在默认视野下会挤成一片，尺寸区间必须压得比"看起来舒服"更小。
  MIN: 2,
  MAX: 9,
  SELECTED_BONUS: 3,
  HOVER_BONUS: 1.5,
};
