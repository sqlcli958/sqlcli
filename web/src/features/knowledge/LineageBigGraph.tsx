import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { deleteLineage, getLineageNetworkGraph } from '../../api/workspace';
import { useDeleteConfirm } from '../../api/useDeleteConfirm';
import { useSessionStore } from '../../state/sessionStore';
import { useGraphStore } from '../../state/graphStore';
import { Button } from '../../ui/Button';
import {
  FlowCanvas,
  Handle,
  Panel,
  Position,
  layoutLeftToRight,
  type Edge,
  type Node,
  type NodeProps,
} from '../../ui/FlowCanvas';
import type { LineageGraphDto } from '../../types/api';
import { LineageRecordCard, ArrowInIcon, ArrowOutIcon } from '../table-inspector/LineageDialog';
import { KIND_COLORS, KIND_ORDER, lineageKindHint, lineageKindLabel, tableOnly } from '../table-inspector/lineageKind';
import { groupByTable, reachable, tableOf, type TableGroup } from './lineageGraphModel';

interface TableNodeData extends TableGroup, Record<string, unknown> {
  selected: string | null;
  /** 选中列的上下游整条路径；为空表示没选中，全亮 */
  lit: Set<string> | null;
  onSelect: (columnId: string) => void;
  /** 点表头：打开右侧表详情（跟表目录、搜索选中一张表是同一个动作） */
  onOpenTable: (tableId: string) => void;
}

type TableNode = Node<TableNodeData, 'table'>;

const NODE_WIDTH = 240;
const HEAD_HEIGHT = 30;
const ROW_HEIGHT = 24;
const FOOT_HEIGHT = 20;

/**
 * 全库血缘大图：一张画布，表是容器，列是容器里的行，边从行画到行。
 *
 * 这是 DataHub / OpenMetadata / Atlan 共同的形状。上一版按「网」拆成一块块竖着堆，
 * 同一张表在两块里各出现一次、块与块之间的空白没有语义——割裂就是这么来的。
 *
 * 点一列：它的上下游整条路径亮着、其余变淡，右侧出两组记录（写进它的 / 它参与写的）。
 * 只列有血缘的列，其余折成「其余 N 列」——Atlan 默认 10 列，OpenMetadata 1.4 默认全开
 * 被投诉 20 到 40 列的表看不动。
 */
export function LineageBigGraph({ focusTableId }: { focusTableId: string | null }) {
  const revision = useSessionStore((s) => s.revision);
  const selectNode = useGraphStore((s) => s.selectNode);
  const [selected, setSelected] = useState<string | null>(null);

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['lineage-graph', revision],
    queryFn: ({ signal }) => getLineageNetworkGraph(signal),
    staleTime: 30_000,
  });

  const lit = useMemo(() => (data && selected ? reachable(data, selected) : null), [data, selected]);
  // 右侧只有一个面板：点列开血缘详情、点表开表详情，开下一个就关上一个。
  // 两个面板各自有一份选中态（列在这里，表在 graphStore），互斥靠开一个时清掉另一个
  const openColumn = (columnId: string) => {
    selectNode(null);
    setSelected(columnId);
  };
  const openTable = (tableId: string) => {
    setSelected(null);
    selectNode(tableId);
  };
  const { nodes, edges } = useMemo(
    () => (data ? buildFlow(data, selected, lit, openColumn, openTable) : { nodes: [], edges: [] }),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 两个 open* 只用到稳定的 setter
    [data, selected, lit],
  );

  const remove = useDeleteConfirm((id) => deleteLineage(id, revision, '手动删除'), () => void refetch());

  if (isLoading) return <p className="lineage-big-hint">加载中…</p>;
  if (isError || !data) return <p className="lineage-big-hint">血缘加载失败</p>;
  if (data.nodes.length === 0) {
    return (
      <p className="lineage-big-hint">
        还没有血缘。血缘不是自动扫描出来的，需要 Agent 读代码确认后用
        <code> schema add-lineage </code>写回。
      </p>
    );
  }

  const records = data.records ?? [];
  const into = selected ? records.filter((r) => r.target === selected) : [];
  const outOf = selected ? records.filter((r) => r.sources.includes(selected)) : [];

  return (
    <div className="lineage-big">
      <div className="lineage-big-canvas">
        <FlowCanvas
          nodes={nodes}
          edges={edges}
          nodeTypes={NODE_TYPES}
          focusNodeId={focusTableId}
          onPaneClick={() => setSelected(null)}
        >
          <Panel position="top-right" className="lineage-legend" aria-label="线段图例">
            {KIND_ORDER.map((kind) => (
              <span key={kind} className="lineage-legend-item" title={lineageKindHint(kind)}>
                <svg width="28" height="8" aria-hidden="true">
                  <line
                    x1="0"
                    y1="4"
                    x2="28"
                    y2="4"
                    stroke={KIND_COLORS[kind]}
                    strokeWidth="2"
                    strokeDasharray={kind === 'rule' ? '5 4' : undefined}
                  />
                </svg>
                {kind}
              </span>
            ))}
          </Panel>
        </FlowCanvas>
      </div>
      {selected && (
        <aside className="lineage-big-detail" aria-label="血缘详情">
          <div className="lineage-big-detail-head">
            <div className="lineage-big-detail-title" title={selected}>
              <strong>{selected.slice(selected.lastIndexOf('.') + 1)}</strong>
              <button
                type="button"
                className="lineage-table-link"
                title={`打开表详情 ${tableOf(selected)}`}
                onClick={() => openTable(tableOf(selected))}
              >
                {tableOnly(tableOf(selected))}
              </button>
            </div>
            <Button size="sm" onClick={() => setSelected(null)} icon title="关闭" aria-label="关闭">
              ×
            </Button>
          </div>
          <h4 className="lineage-section-title">
            <ArrowInIcon /> Upstream · {into.length}
          </h4>
          {into.length === 0 && <p className="lineage-big-hint">源头列，值是录进来的</p>}
          {into.map((record) => (
            <LineageRecordCard key={record.id} record={record} {...remove.stateOf(record.id)} />
          ))}
          <h4 className="lineage-section-title">
            <ArrowOutIcon /> Downstream · {outOf.length}
          </h4>
          {outOf.length === 0 && <p className="lineage-big-hint">改它不影响别的列的写入</p>}
          {outOf.map((record) => (
            <LineageRecordCard key={record.id} record={record} showTarget {...remove.stateOf(record.id)} />
          ))}
        </aside>
      )}
    </div>
  );
}

function buildFlow(
  graph: LineageGraphDto,
  selected: string | null,
  lit: Set<string> | null,
  onSelect: (id: string) => void,
  onOpenTable: (tableId: string) => void,
): { nodes: TableNode[]; edges: Edge[] } {
  const nodes: TableNode[] = groupByTable(graph).map((group) => ({
    id: group.id,
    type: 'table',
    position: { x: 0, y: 0 },
    width: NODE_WIDTH,
    height: HEAD_HEIGHT + group.columns.length * ROW_HEIGHT + (group.otherColumns > 0 ? FOOT_HEIGHT : 0),
    data: { ...group, selected, lit, onSelect, onOpenTable },
  }));

  const edges: Edge[] = graph.edges
    .filter((edge) => tableOf(edge.from) !== tableOf(edge.to))
    .map((edge, index) => {
      const dim = lit !== null && !(lit.has(edge.from) && lit.has(edge.to));
      const title = [edge.lineageKind ? lineageKindLabel(edge.lineageKind) : null, edge.expression, edge.through]
        .filter(Boolean)
        .join(' · ');
      return {
        id: `${edge.id ?? index}:${edge.from}>${edge.to}`,
        source: tableOf(edge.from),
        target: tableOf(edge.to),
        sourceHandle: edge.from,
        targetHandle: edge.to,
        className: `lineage-flow-edge is-${edge.lineageKind ?? 'unknown'}${dim ? ' is-dim' : ''}`,
        data: { title },
      };
    });

  return { nodes: layoutLeftToRight(nodes, edges), edges };
}

/** 表容器：表头 + 有血缘的列各一行（左右各一个端口）+ 折叠的其余列 */
function TableNodeView({ data }: NodeProps<TableNode>) {
  return (
    <div className="lineage-table-node">
      <button
        type="button"
        className="lineage-table-node-head"
        title={`打开表详情 ${data.schema}.${data.table}`}
        onClick={() => data.onOpenTable(`${data.schema}.${data.table}`)}
      >
        {data.table}
      </button>
      {data.columns.map((column) => {
        const dim = data.lit !== null && !data.lit.has(column.id);
        const isSelected = data.selected === column.id;
        return (
          <div
            key={column.id}
            className={`lineage-table-node-row${isSelected ? ' is-selected' : ''}${dim ? ' is-dim' : ''}`}
            role="button"
            tabIndex={0}
            title={column.id}
            onClick={() => data.onSelect(column.id)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                data.onSelect(column.id);
              }
            }}
          >
            <Handle type="target" position={Position.Left} id={column.id} className="lineage-port" />
            <span className="lineage-table-node-column">{column.name}</span>
            {column.internalSources.length > 0 && (
              <span
                className="lineage-table-node-internal"
                title={`由本表 ${column.internalSources.map((s) => s.slice(s.lastIndexOf('.') + 1)).join('、')} 算出`}
              >
                ↺
              </span>
            )}
            <Handle type="source" position={Position.Right} id={column.id} className="lineage-port" />
          </div>
        );
      })}
      {data.otherColumns > 0 && <div className="lineage-table-node-foot">其余 {data.otherColumns} 列</div>}
    </div>
  );
}

const NODE_TYPES = { table: TableNodeView };
