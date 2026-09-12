import { Button } from './Button';
import { Select } from './Select';

/** 每页条数选项。再多就该用筛选缩小范围了，不是翻更大的页。 */
const PAGE_SIZES = [20, 50, 100];

/**
 * 全站唯一的分页条，放在列表右上角（筛选条的右端）。
 *
 * 之前每个列表自己写一遍「上一页 / 第 N 页 / 下一页」，翻页的边界判断也各写一次；
 * 后端给了 total 之后总页数、末页禁用这些都是同一份算术，没有第二种写法。
 *
 * 总条数放在页码的 title 里而不是单独占一格——列表本身就在旁边，
 * 页码已经说明了规模，正文里再写一遍「共 196 条」是重复（CLAUDE.md「文字克制」）。
 */
export function Pagination({
  page,
  pageSize,
  total,
  onPage,
  onPageSize,
}: {
  /** 0 基页码 */
  page: number;
  pageSize: number;
  total: number;
  onPage: (page: number) => void;
  onPageSize?: (pageSize: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / pageSize));

  return (
    <nav className="pager" aria-label="分页">
      {onPageSize && (
        <Select
          size="sm"
          value={pageSize}
          label="每页条数"
          title="每页条数"
          onChange={(event) => onPageSize(Number(event.target.value))}
        >
          {PAGE_SIZES.map((size) => (
            <option key={size} value={size}>{size} 条/页</option>
          ))}
        </Select>
      )}
      <Button size="sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
        上一页
      </Button>
      <span className="pager-page" title={`共 ${total} 条`}>
        第 {page + 1} / {pages} 页
      </span>
      <Button size="sm" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>
        下一页
      </Button>
    </nav>
  );
}
