import { memo } from 'react';
import { RELATION_COLORS } from '../../graph/graphStyles';
import type { RelationType } from '../../types/api';

interface RelationTypeBadgeProps {
  type: string;
  size?: 'sm' | 'md';
}

/**
 * 标签直接用枚举原值：类型名本身就是契约，翻译成中文反而对不上 CLI 和 API。
 *
 * 颜色从 `RELATION_COLORS` 取，不在这里再写一份——同一个关系在图上是一种颜色、
 * 在列表里是另一种，比没有颜色更糟。
 */
function getBadge(type: string): { label: string; color: string } {
  const color = RELATION_COLORS[type as RelationType];
  return { label: type, color: color ?? '#94a3b8' };
}

export const RelationTypeBadge = memo(function RelationTypeBadge({
  type,
  size = 'sm',
}: RelationTypeBadgeProps) {
  const { label, color } = getBadge(type);
  return (
    <span
      className={`relation-type-badge relation-type-badge-${size}`}
      style={{ backgroundColor: color }}
    >
      {label}
    </span>
  );
});
