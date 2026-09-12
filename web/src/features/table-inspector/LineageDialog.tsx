import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { deleteLineage, getColumnLineageGraph } from '../../api/workspace';
import { useDeleteConfirm } from '../../api/useDeleteConfirm';
import { useSessionStore } from '../../state/sessionStore';
import { ActionIcon } from '../../ui/ActionIcon';
import { Button } from '../../ui/Button';
import { Dialog } from '../../ui/Dialog';
import { Select } from '../../ui/Select';
import { StatusDot, type Tone } from '../../ui/StatusDot';
import type { LineageRecordDto } from '../../types/api';
import { ColumnLineagePanel } from './TableInspector';
import { lineageKindHint, lineageKindLabel, shortRef } from './lineageKind';

/**
 * 字段血缘详情弹窗，两个入口共用：表详情的字段血缘按钮，图谱页左栏血缘列表的每一行。
 *
 * 上半是**记录**：写进这一列的每一条血缘各一块——类型和它的处置、上游、公式、代码位置、
 * 状态与时间，删除也在块里。这是人核对的单位。
 * 下半是**图**：多跳展开，看这个值往上追到哪、往下影响谁。图上的边按源列展开，
 * 不承担详情，详情在上面。
 */
export function LineageDialog({
  open,
  onOpenChange,
  tableRef,
  columnName,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** `schema.table`，后端按它解析表 */
  tableRef: string;
  columnName: string;
}) {
  const [depth, setDepth] = useState(2);
  const revision = useSessionStore((s) => s.revision);
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['lineage', tableRef, columnName, depth],
    queryFn: ({ signal }) => getColumnLineageGraph(tableRef, columnName, depth, signal),
    staleTime: 60_000,
    enabled: open,
  });
  const remove = useDeleteConfirm((id) => deleteLineage(id, revision, '手动删除'), () => void refetch());

  const records = data?.records ?? [];

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      wide
      title={`字段血缘 · ${columnName}`}
      description={tableRef}
    >
      <div className="column-lineage">
        {isLoading && <span className="spinner" />}
        {isError && <p className="lineage-empty">血缘加载失败</p>}

        {records.map((record) => (
          <LineageRecordCard key={record.id} record={record} {...remove.stateOf(record.id)} />
        ))}

        {data && (
          <section className="lineage-graph-section">
            <div className="lineage-graph-bar">
              <h3 className="lineage-section-title">血缘图</h3>
              <label className="lineage-depth-select">
                <span className="desc-editor-label">展开深度</span>
                <Select size="sm" value={depth} onChange={(e) => setDepth(Number(e.target.value))}>
                  <option value={1}>1 跳</option>
                  <option value={2}>2 跳</option>
                  <option value={3}>3 跳</option>
                  <option value={4}>4 跳</option>
                  <option value={5}>5 跳</option>
                </Select>
              </label>
            </div>
            <ColumnLineagePanel graph={data} targetRef={`${tableRef}.${columnName}`} />
          </section>
        )}
      </div>
    </Dialog>
  );
}

/** 进入这一列的箭头：上游。12px 内联，跟文字同色 */
export function ArrowInIcon() {
  return (
    <svg className="lineage-arrow-icon" width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M4 11h10.2l-3.6-3.6L12 6l6 6-6 6-1.4-1.4 3.6-3.6H4z" />
      <path d="M20 5h2v14h-2z" />
    </svg>
  );
}

/** 从这一列出去的箭头：下游 */
export function ArrowOutIcon() {
  return (
    <svg className="lineage-arrow-icon" width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M2 5h2v14H2z" />
      <path d="M8 11h10.2l-3.6-3.6L16 6l6 6-6 6-1.4-1.4 3.6-3.6H8z" />
    </svg>
  );
}

/** 公式 / 规则的记号：ƒ。跟两个箭头同一套 12px 内联图标 */
function FormulaIcon() {
  return (
    <svg className="lineage-arrow-icon" width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M13.5 3c-2 0-3.2 1.2-3.6 3.4L9.6 8H7v2h2.3l-1.4 8.2c-.2 1.1-.6 1.6-1.4 1.6-.4 0-.8-.1-1.1-.3L5 21.2c.5.3 1.2.5 1.9.5 2 0 3.2-1.2 3.6-3.4L11.9 10H15V8h-2.8l.3-1.5c.2-1.1.6-1.6 1.4-1.6.4 0 .8.1 1.1.3l.4-1.7c-.5-.3-1.2-.5-1.9-.5z" />
    </svg>
  );
}
/**
 * 状态灯三色（CLAUDE.md：绿=正常，黄=可用但需注意，红=不可用）。
 * 绿只给人工确认过的；候选和「发布了但没人复核」都是黄，两者靠悬浮文字分；拒绝红。
 */
function statusTone(status?: string | null): { tone: Tone; label: string } {
  switch (status) {
    case 'candidate':
      return { tone: 'warn', label: '候选，待发布' };
    case 'ignored':
      return { tone: 'bad', label: '已忽略' };
    case 'verified':
      return { tone: 'ok', label: '已发布，人工确认' };
    default:
      return { tone: 'warn', label: '已发布，未人工确认' };
  }
}

/**
 * 一条记录一块。标题行 = 类型标签 + 代码位置（去哪改，是这块最要紧的信息）；
 * 下面对齐的两三行：写入（只在看「它参与写的」时）、上游、规则或公式；
 * 最后一行状态 · 置信度 · 时间。类型的处置那句进标签的悬浮，不在每张卡上重复。
 */
export function LineageRecordCard({
  record,
  confirming,
  deleting,
  error,
  onAskDelete,
  onCancel,
  onConfirm,
  showTarget = false,
}: {
  record: LineageRecordDto;
  confirming: boolean;
  deleting: boolean;
  error: string | null;
  onAskDelete: () => void;
  onCancel: () => void;
  onConfirm: () => void;
  showTarget?: boolean;
}) {
  return (
    <section className="lineage-record" aria-label={`血缘记录 ${lineageKindLabel(record.lineageKind)}`}>
      <div className="lineage-record-meta">
        <StatusDot tone={statusTone(record.status).tone} label={statusTone(record.status).label} />
        {record.confidence != null && <span>置信度 {Math.round(record.confidence * 100)}%</span>}
        {record.updatedAt && <time dateTime={record.updatedAt}>{new Date(record.updatedAt).toLocaleString()}</time>}
        <span className="lineage-record-spacer" />
        {confirming ? (
          <span className="lineage-list-confirm">
            <Button variant="danger" size="sm" disabled={deleting} onClick={onConfirm}>
              {deleting ? '删除中…' : '确认删除'}
            </Button>
            <Button size="sm" disabled={deleting} onClick={onCancel}>
              取消
            </Button>
          </span>
        ) : (
          <ActionIcon action="delete" label="删除这条血缘" onClick={onAskDelete} />
        )}
      </div>
      {/* 类型 + 代码位置：代码位置整段显示、允许折行——它是「去哪改」，截掉就没用了 */}
      <div className="lineage-record-head">
        <span
          className={`lineage-kind is-${record.lineageKind ?? 'unknown'}`}
          title={`${lineageKindLabel(record.lineageKind)}：${lineageKindHint(record.lineageKind)}`}
        >
          {record.lineageKind ?? '未分类'}
        </span>
        <pre className="lineage-record-expression lineage-record-through">{record.through ?? '未记代码位置'}</pre>
      </div>
      <div className="lineage-record-body">
        {showTarget && record.target && (
          <div className="lineage-record-row">
            <span className="lineage-record-icon" title="写入" aria-label="写入"><ArrowOutIcon /></span>
            <span className="lineage-record-source" title={record.target}>{shortRef(record.target)}</span>
          </div>
        )}
        <div className="lineage-record-row">
          <span className="lineage-record-icon" title="上游" aria-label="上游"><ArrowInIcon /></span>
          <span className="lineage-record-chips">
            {record.sources.map((source) => (
              <span key={source} className="lineage-record-source" title={source}>
                {shortRef(source)}
              </span>
            ))}
          </span>
        </div>
        {record.expression && (
          <div className="lineage-record-row is-block">
            <span
              className="lineage-record-icon"
              title={record.lineageKind === 'rule' ? '规则' : '公式'}
              aria-label={record.lineageKind === 'rule' ? '规则' : '公式'}
            >
              <FormulaIcon />
            </span>
            <pre className="lineage-record-expression">{record.expression}</pre>
          </div>
        )}
      </div>
      {error && <p className="lineage-record-line lineage-list-error">{error}</p>}
    </section>
  );
}
