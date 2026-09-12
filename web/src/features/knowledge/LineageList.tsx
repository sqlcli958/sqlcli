import { useQuery } from '@tanstack/react-query';
import { getLineageNetworkGraph } from '../../api/workspace';
import { useSessionStore } from '../../state/sessionStore';
import { tableOnly } from '../table-inspector/lineageKind';
import { ArrowInIcon, ArrowOutIcon } from '../table-inspector/LineageDialog';

/**
 * 左栏血缘索引：有血缘的表一行，几列参与、几条记录写进它。
 * 点一行，主画布的大图把视野移到那张表。
 *
 * 索引按表：表目录也按表，人从表目录切过来不用换脑子。「网」（连通分量）试过一版，
 * 它把一张画布拆成一块块，同一张表在两块里各出现一次，已撤。
 */
export function LineageList({ onFocus }: { onFocus: (tableId: string) => void }) {
  const revision = useSessionStore((s) => s.revision);
  const { data, isLoading, isError } = useQuery({
    queryKey: ['lineage-graph', revision],
    queryFn: ({ signal }) => getLineageNetworkGraph(signal),
    staleTime: 30_000,
  });

  if (isLoading) return <p className="term-list-hint">加载中…</p>;
  if (isError) return <p className="term-list-hint">血缘加载失败</p>;

  const tables = new Map<string, { columns: number; records: number }>();
  for (const node of data?.nodes ?? []) {
    const id = `${node.schema}.${node.table}`;
    const entry = tables.get(id) ?? { columns: 0, records: 0 };
    entry.columns += 1;
    tables.set(id, entry);
  }
  for (const record of data?.records ?? []) {
    if (!record.target) continue;
    const id = record.target.slice(0, record.target.lastIndexOf('.'));
    const entry = tables.get(id);
    if (entry) entry.records += 1;
  }

  if (tables.size === 0) {
    return (
      <p className="term-list-hint">
        还没有血缘。血缘不是自动扫描出来的，需要 Agent 读代码（Mapper / ETL / 视图定义）
        确认推导关系后用 <code>sql-cli &lt;alias&gt; schema add-lineage</code> 写回。
      </p>
    );
  }

  const rows = Array.from(tables.entries()).sort((a, b) => b[1].records - a[1].records || a[0].localeCompare(b[0]));

  return (
    <ul className="term-list" aria-label="有血缘的表">
      {rows.map(([id, entry]) => (
        <li key={id} className="term-list-item">
          <button
            type="button"
            className="lineage-list-row"
            title={`在大图里定位 ${id}`}
            aria-label={`定位 ${id}`}
            onClick={() => onFocus(id)}
          >
            <span className="term-list-name">{tableOnly(id)}</span>
            {/* 图标跟右侧详情同一对：写入它的（↦|）几条、它的列参与了（|↦）几处 */}
            <span className="lineage-list-table lineage-list-counts">
              <span title={`${entry.records} 条写入这张表`} aria-label={`${entry.records} 条写入`}>
                <ArrowInIcon /> {entry.records}
              </span>
              <span title={`${entry.columns} 列参与血缘`} aria-label={`${entry.columns} 列参与`}>
                <ArrowOutIcon /> {entry.columns}
              </span>
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}
