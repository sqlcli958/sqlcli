import type { ReactNode } from 'react';

/**
 * 列表上方的工具条：左边筛选项，右边分页条（`Pagination` 自带 `margin-left: auto`）。
 *
 * 收成组件是因为它出现在多个列表上方，而各写一遍的结果已经见过——
 * `.exec-filters` 和 `.review-filter` 曾经是两套间距不同的实现。
 */
export function FilterBar({ children }: { children: ReactNode }) {
  return <div className="filter-bar">{children}</div>;
}
