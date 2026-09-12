import { useState, useCallback } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { getTableDetail, patchTable, patchColumn, getRowCount } from '../../api/workspace';
import { LineageDialog } from './LineageDialog';
import { lineageKindLabel } from './lineageKind';
import { importAliasGraph } from '../../api/aliases';
import { useGraphStore } from '../../state/graphStore';
import { onMutationSuccess } from '../../api/mutationHelpers';
import { DescriptionEditor } from './DescriptionEditor';
import { RelationList } from '../relation-editor/RelationList';
import { RelationEditor } from '../relation-editor/RelationEditor';
import { SEMANTIC_TYPE_GROUPS } from '../../types/api';
import type {
  ColumnDto,
  LineageGraphDto,
  MutationResultDto,
  SemanticType,
  TableIndexDto,
} from '../../types/api';
import { ActionIcon } from '../../ui/ActionIcon';
import { navPath } from '../../app/navigation';
import { selectSampleSql, stashWorkbenchSql } from '../workbench/workbenchSql';
import { Button } from '../../ui/Button';
import { StatusDot } from '../../ui/StatusDot';
import { Select } from '../../ui/Select';

/** 刷新图标，表结构刷新和行数采集共用。 */
function RefreshIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M17.65 6.35A8 8 0 1 0 19.73 14h-2.08A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" />
    </svg>
  );
}

/** 终端提示符，和工作台导航图标同一套语言。 */
function QueryIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 5h18v14H3z M7 10l2 2-2 2 M13 15h4" />
    </svg>
  );
}

/** 血缘图标：一条带箭头的支线，和「关系」概念区分开（关系是表级，血缘是列级）。 */
function LineageIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 4v16M4 12h8m0 0l-3-3m3 3l-3 3M16 8h4v4h-4z" />
    </svg>
  );
}

/** 一列的方向标题：level 就是第几跳，负上游正下游。 */
function levelLabel(level: number): string {
  if (level === 0) return '当前字段';
  return level < 0 ? `上游 ${-level} 跳` : `下游 ${level} 跳`;
}

/** 列名太长会撑破节点框，超了就截断，全名在 <title> 里。 */
function clip(text: string | undefined, max: number): string {
  if (!text) return '';
  return text.length <= max ? text : text.slice(0, max - 1) + '…';
}

/**
 * 单字段血缘图：SVG 自绘。
 *
 * **为什么不引图可视化库**（React Flow / cytoscape 之类）：这张图是只读的——不拖节点、
 * 不编辑、不需要 minimap。它需要的（正确的箭头、看得懂的方向、长链路能横向滚）全是
 * 「长什么样」，而库卖的是「怎么动」。判据跟 `ui/Menu.tsx` 引 Radix 是同一条，
 * 只是结论相反：那边手写会漏六件行为，这边手写只是算三个坐标。
 * 后端 `LINEAGE_MAX_NODES` 封顶 40，规模也撑不起一个布局引擎。
 *
 * **连线止于节点边缘，不是节点中心。** 这是之前「看不到方向」的根因：端点取中心时，
 * 箭头落在 rect 正中间，而节点在边之后绘制，箭头被整个盖住——线画了、marker 也画了，
 * 就是看不见。
 *
 * **正交折线而不是直连。** 多个上游扇入同一个目标时，直连是几条交叉的斜线；
 * 折线让它们汇到同一条竖干上再进目标，节点一多就是这个差别。
 *
 * **空态是这个功能最常见的状态**：多数图谱里 `lineage.jsonl` 是空的，所以必须说清楚
 * 血缘怎么来、下一步敲什么命令，而不是一句「暂无数据」。
 */
export function ColumnLineagePanel({ graph, targetRef }: { graph: LineageGraphDto; targetRef: string }) {
  if (graph.nodes.length <= 1) {
    return (
      <div className="lineage-empty">
        <p>
          该字段暂无血缘记录。血缘不是自动扫描出来的，需要 Agent 读代码（Mapper/ETL/视图定义）
          确认推导关系后写回图谱：
        </p>
        <code className="lineage-hint-cmd">
          {`schema add-lineage --target ${targetRef} --source <schema.table.column>[,...] --through <类名.方法> [--kind identity|transformation|aggregation|rule] [--expression E]`}
        </code>
      </div>
    );
  }

  const levels = Array.from(new Set(graph.nodes.map((n) => n.level))).sort((a, b) => a - b);
  const colWidth = 220;
  const nodeWidth = 168;
  const halfWidth = nodeWidth / 2;
  const nodeHeight = 40;
  const headerHeight = 26;

  /**
   * 每个目标节点收到的推导说明，**按目标去重**。
   *
   * expression 描述的是「这条血缘记录」——一条记录有 3 个上游就会展开成 3 条边，
   * 逐边画等于把同一句话画 3 遍，还全都压在共用的那条竖干上。它属于目标字段，
   * 画在目标节点下面一次就够。
   */
  const expressionsInto = new Map<string, string[]>();
  for (const edge of graph.edges) {
    if (!edge.expression) continue;
    const notes = expressionsInto.get(edge.to) ?? [];
    if (!notes.includes(edge.expression)) notes.push(edge.expression);
    expressionsInto.set(edge.to, notes);
  }
  const maxNotes = Math.max(0, ...Array.from(expressionsInto.values(), (n) => n.length));

  const nodesByLevel = new Map<number, LineageGraphDto['nodes']>();
  for (const level of levels) {
    nodesByLevel.set(level, graph.nodes.filter((n) => n.level === level));
  }
  const maxRows = Math.max(...levels.map((level) => nodesByLevel.get(level)!.length));
  // 行高要吃下节点本身 + 它下面的推导说明，否则说明会压到下一行的节点上
  const rowHeight = 64 + maxNotes * 12;
  const width = levels.length * colWidth;
  const height = headerHeight + maxRows * rowHeight + 16;

  const pos = new Map<string, { x: number; y: number }>();
  levels.forEach((level, colIndex) => {
    const rows = nodesByLevel.get(level)!;
    // 每列在自己的高度里居中，列与列之间才对得齐
    const offset = (maxRows - rows.length) * rowHeight / 2;
    rows.forEach((node, rowIndex) => {
      pos.set(node.id, {
        x: colIndex * colWidth + colWidth / 2,
        y: headerHeight + offset + rowIndex * rowHeight + rowHeight / 2,
      });
    });
  });

  return (
    <div className="lineage-graph-wrap">
      <svg className="lineage-graph-svg" width={width} height={height} role="img" aria-label="字段血缘图">
        <defs>
          {/* userSpaceOnUse：箭头大小不跟着 stroke-width 缩放，换线宽时不会突然变巨大 */}
          <marker
            id="lineage-arrow"
            markerWidth="9"
            markerHeight="9"
            refX="8"
            refY="4.5"
            orient="auto"
            markerUnits="userSpaceOnUse"
          >
            <path d="M0,1 L8,4.5 L0,8 z" className="lineage-arrow-head" />
          </marker>
        </defs>

        {/* 列头：不写「上游/下游」的话，左右两边是什么全靠猜 */}
        {levels.map((level, colIndex) => (
          <text
            key={`head-${level}`}
            x={colIndex * colWidth + colWidth / 2}
            y={14}
            className={`lineage-col-head${level === 0 ? ' is-center' : ''}`}
          >
            {levelLabel(level)}
          </text>
        ))}

        {graph.edges.map((edge, i) => {
          const from = pos.get(edge.from);
          const to = pos.get(edge.to);
          if (!from || !to) return null;
          // 出右边缘、进左边缘——端点取中心时箭头会被目标节点的 rect 盖住
          const x1 = from.x + halfWidth;
          const x2 = to.x - halfWidth;
          const midX = (x1 + x2) / 2;
          // 横 → 竖 → 横：多条边共用中间那段竖干，扇入时不会交叉成一团
          const path = `M ${x1},${from.y} H ${midX} V ${to.y} H ${x2}`;
          // 旧记录没有类别：悬浮里不写「未分类」占位，只在有类别时前置
          const detail = [edge.lineageKind ? lineageKindLabel(edge.lineageKind) : null, edge.expression, edge.through]
            .filter(Boolean).join(' · ');
          // rule 画虚线：它只影响写不写，不提供值——跟提供值的三类混成一样的实线，
          // 读图的人会把条件当成值来源
          return (
            <g key={i} className={`lineage-edge is-${edge.lineageKind ?? 'unknown'}`}>
              {detail && <title>{detail}</title>}
              <path d={path} markerEnd="url(#lineage-arrow)" />
            </g>
          );
        })}

        {graph.nodes.map((node) => {
          const p = pos.get(node.id);
          if (!p) return null;
          const notes = expressionsInto.get(node.id) ?? [];
          return (
            <g
              key={node.id}
              transform={`translate(${p.x},${p.y})`}
              className={`lineage-node${node.level === 0 ? ' lineage-node-center' : ''}`}
            >
              <title>{node.id}</title>
              <rect
                x={-halfWidth}
                y={-nodeHeight / 2}
                width={nodeWidth}
                height={nodeHeight}
                rx={5}
              />
              <text x={0} y={-3} className="lineage-node-table">{clip(node.table, 24)}</text>
              <text x={0} y={11} className="lineage-node-column">{clip(node.column, 20)}</text>
              {notes.map((note, index) => (
                <text
                  key={note}
                  x={0}
                  y={nodeHeight / 2 + 13 + index * 12}
                  className="lineage-node-note"
                >
                  {/* 只有截断了才需要悬浮看全文；没截断时再挂一个 title 会让同一段文字出现两次 */}
                  {note.length > 26 && <title>{note}</title>}
                  {clip(note, 26)}
                </text>
              ))}
            </g>
          );
        })}
      </svg>
      {graph.truncated && <p className="lineage-truncated-note">节点较多，已收起一部分——缩小深度更容易看清。</p>}
    </div>
  );
}

// ── 可展开的字段行 ──

interface ColumnRowProps {
  /** 直接用 ColumnDto,不再抄一份子集——抄出来的那份漏了 valueHints,而且没人会记得同步 */
  column: ColumnDto;
  tableId: string;
  /** 表的 schema.table，用来拼血缘的 target ref（空态提示文案要用） */
  tableQualifiedName: string;
  revision: number;
  onRefetch: () => void;
}

/** 类型长度：字符型看 length，带小数位的数值型看 precision,scale。 */
function typeLength(dataType: ColumnRowProps['column']['dataType']): string {
  if (dataType.precision != null && dataType.scale != null && dataType.scale > 0) {
    return `${dataType.precision},${dataType.scale}`;
  }
  if (dataType.length != null && dataType.length > 0) {
    return String(dataType.length);
  }
  return '—';
}

function ColumnRow({ tableId, tableQualifiedName, column, revision, onRefetch }: ColumnRowProps) {
  const [expanded, setExpanded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lineageOpen, setLineageOpen] = useState(false);

  const patchField = useCallback(
    async (patch: {
      businessName?: string;
      description?: string;
      semanticType?: SemanticType | null;
      enumValues?: string[];
      format?: string | null;
      sampleValues?: string[];
    }) => {
      setSaving(true);
      setError(null);
      try {
        const result = await patchColumn(tableId, column.name, {
          expectedRevision: revision,
          patch,
          reason: `编辑字段 ${column.name}`,
        });
        onMutationSuccess(result);
        onRefetch();
      } catch (err: unknown) {
        setError(err instanceof Error ? err.message : '保存失败');
      } finally {
        setSaving(false);
      }
    },
    [tableId, column.name, revision, onRefetch],
  );

  return (
    <>
      <tr
        className={`column-row ${expanded ? 'column-row-expanded' : ''}`}
        onClick={() => setExpanded(!expanded)}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') setExpanded(!expanded);
        }}
        title="点击展开，可维护业务名、业务描述与语义类型"
      >
        <td className="col-name">
          <span className={`column-expand-chevron ${expanded ? 'expanded' : ''}`}>
            &#9662;
          </span>
          {column.name}
          {column.primaryKey && <span className="col-pk-badge" title="主键">主键</span>}
          <Button
            title="查看血缘"
            aria-label={`查看 ${column.name} 的血缘`}
            onClick={(e) => {
              e.stopPropagation();
              setLineageOpen((v) => !v);
            }}
            size="sm"
            icon
            className="col-lineage-toggle"
          >
            <LineageIcon />
          </Button>
        </td>
        <td className="col-type">{column.dataType.raw}</td>
        <td className="col-len">{typeLength(column.dataType)}</td>
        <td className="col-null">
          <NullableDot nullable={column.nullable} />
        </td>
        <td className="col-comment-cell">
          {column.comment ? (
            <span className="col-comment-text">{column.comment}</span>
          ) : (
            <span className="col-comment-empty">—</span>
          )}
        </td>
      </tr>
      {lineageOpen && (
        <LineageDialog
          open
          onOpenChange={setLineageOpen}
          tableRef={tableQualifiedName}
          columnName={column.name}
        />
      )}
      {expanded && (
        <tr className="column-detail-row">
          <td colSpan={5}>
            <div className="column-detail-content">
              {error && <div className="column-edit-error">{error}</div>}
              <DescriptionEditor
                label="业务名"
                value={column.businessName}
                placeholder="未填写业务名"
                multiline={false}
                onSave={async (newValue) => patchField({ businessName: newValue })}
                readOnly={saving}
              />
              <DescriptionEditor
                label="业务描述"
                value={column.description ?? undefined}
                placeholder="未填写业务描述"
                multiline={true}
                onSave={async (newValue) => patchField({ description: newValue })}
                readOnly={saving}
              />
              <label className="semantic-select">
                <span className="desc-editor-label">语义类型</span>
                <Select
                  value={column.semanticType ?? ''}
                  disabled={saving}
                  onChange={(event) =>
                    patchField({ semanticType: (event.target.value || null) as SemanticType | null })
                  }
                >
                  <option value="">未标注</option>
                  {SEMANTIC_TYPE_GROUPS.map((group) => (
                    <optgroup key={group.label} label={group.label}>
                      {group.options.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </optgroup>
                  ))}
                </Select>
              </label>
              <ValueHints hints={column.valueHints} onSave={patchField} saving={saving} />
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

/**
 * 值域展示与编辑。值域多数由 Agent 从列注释、代码或采样补进来，人只需要核对，
 * 所以默认停在只读展示；「编辑」按钮切进表单，和 CLI `schema edit --enum-values`
 * 走同一份后端校验（重复值/空值拒绝），语义不会分叉。
 *
 * 没填也要把这一栏画出来：同一块展开详情里，业务名 / 业务描述显示「未填写…」、
 * 语义类型显示「未标注」，值域却整块消失的话，看的人没法判断是这个字段没值域、
 * 还是根本没这个功能。空态顺带把补录命令写出来。
 */
function ValueHints({
  hints,
  onSave,
  saving,
}: {
  hints?: ColumnDto['valueHints'];
  onSave: (patch: { enumValues?: string[]; format?: string | null; sampleValues?: string[] }) => Promise<void>;
  saving: boolean;
}) {
  const [editing, setEditing] = useState(false);
  const enums = hints?.enumValues ?? [];
  const samples = hints?.sampleValues ?? [];

  if (editing) {
    return (
      <ValueHintsEditor
        hints={hints}
        saving={saving}
        onCancel={() => setEditing(false)}
        onSave={async (patch) => {
          await onSave(patch);
          setEditing(false);
        }}
      />
    );
  }

  return (
    <div className="col-hints">
      <span className="desc-editor-label">
        值域
        <ActionIcon action="edit" label="编辑值域" onClick={() => setEditing(true)} />
      </span>
      {!enums.length && !samples.length && !hints?.format ? (
        <p className="col-hints-line col-hints-empty">
          未填写。状态 / 类型 / 标志类字段建议补上，否则查询只能猜字面量。
        </p>
      ) : (
        <>
          {enums.length > 0 && (
            <ul className="col-hints-enum">
              {enums.map((item) => {
                // 约定存成「值=含义」；没写含义时只显示值，不补破折号占位
                const split = item.indexOf('=');
                const value = split < 0 ? item : item.slice(0, split);
                const label = split < 0 ? null : item.slice(split + 1);
                return (
                  <li key={item}>
                    <code>{value}</code>
                    {label && <span>{label}</span>}
                  </li>
                );
              })}
            </ul>
          )}
          {hints?.format && <p className="col-hints-line">格式：{hints.format}</p>}
          {samples.length > 0 && (
            <p className="col-hints-line">样例：{samples.join('、')}</p>
          )}
        </>
      )}
    </div>
  );
}

/** 值域编辑表单：一行一个「值=含义」，格式和样例各一个输入框。 */
function ValueHintsEditor({
  hints,
  saving,
  onSave,
  onCancel,
}: {
  hints?: ColumnDto['valueHints'];
  saving: boolean;
  onSave: (patch: { enumValues: string[]; format: string | null; sampleValues: string[] }) => Promise<void>;
  onCancel: () => void;
}) {
  const [enumText, setEnumText] = useState((hints?.enumValues ?? []).join('\n'));
  const [format, setFormat] = useState(hints?.format ?? '');
  const [samplesText, setSamplesText] = useState((hints?.sampleValues ?? []).join('、'));
  const [error, setError] = useState<string | null>(null);

  return (
    <div className="col-hints col-hints-editing">
      <span className="desc-editor-label">值域</span>
      {error && <div className="column-edit-error">{error}</div>}
      <label className="col-hints-field">
        <span>枚举值（一行一个，如 0=待付款）</span>
        <textarea
          rows={3}
          value={enumText}
          disabled={saving}
          onChange={(e) => setEnumText(e.target.value)}
        />
      </label>
      <label className="col-hints-field">
        <span>格式</span>
        <input value={format} disabled={saving} onChange={(e) => setFormat(e.target.value)} />
      </label>
      <label className="col-hints-field">
        <span>样例（顿号分隔）</span>
        <input value={samplesText} disabled={saving} onChange={(e) => setSamplesText(e.target.value)} />
      </label>
      <div className="col-hints-actions">
        <Button
          disabled={saving}
          onClick={async () => {
            setError(null);
            try {
              await onSave({
                enumValues: enumText.split('\n').map((s) => s.trim()).filter(Boolean),
                format: format.trim() || null,
                sampleValues: samplesText.split(/[、,]/).map((s) => s.trim()).filter(Boolean),
              });
            } catch (err: unknown) {
              setError(err instanceof Error ? err.message : '保存失败');
            }
          }}
          variant="primary"
          size="sm"
        >
          保存
        </Button>
        <Button disabled={saving} onClick={onCancel} variant="ghost" size="sm">
          取消
        </Button>
      </div>
    </div>
  );
}

// ── 主组件 ──

export function TableInspector() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const alias = searchParams.get('alias');
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const selectNode = useGraphStore((s) => s.selectNode);
  const workspaceRevision = useGraphStore((s) => s.workspaceRevision);
  const queryClient = useQueryClient();

  const tableId = selectedNodeId;

  const {
    data: tableDetail,
    isLoading,
    isError,
    refetch,
  } = useQuery({
    queryKey: ['table', tableId],
    queryFn: ({ signal }) => getTableDetail(tableId!, signal),
    enabled: !!tableId,
    staleTime: 60_000,
  });

  const [showAddRelation, setShowAddRelation] = useState(false);

  const patchBusinessName = useMutation({
    mutationFn: (businessName: string) =>
      patchTable(tableId!, {
        expectedRevision: workspaceRevision,
        patch: { businessName },
        reason: `编辑表 ${tableId} 的业务名`,
      }),
    onSuccess: (result) => {
      onMutationSuccess(result);
      queryClient.invalidateQueries({ queryKey: ['table', tableId] });
      queryClient.invalidateQueries({ queryKey: ['graph'] });
    },
  });

  const patchDescription = useMutation({
    mutationFn: (description: string) =>
      patchTable(tableId!, {
        expectedRevision: workspaceRevision,
        patch: { description },
        reason: `编辑表 ${tableId} 的业务描述`,
      }),
    onSuccess: (result) => {
      onMutationSuccess(result);
      queryClient.invalidateQueries({ queryKey: ['table', tableId] });
      queryClient.invalidateQueries({ queryKey: ['graph'] });
    },
  });

  /** 单表刷新：重新读字段、类型、注释；业务名、业务描述、语义类型和自建关系不覆盖。 */
  const refresh = useMutation({
    mutationFn: (target: { schema: string; table: string }) => importAliasGraph(alias!, target),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['table', tableId] });
      queryClient.invalidateQueries({ queryKey: ['graph'] });
      queryClient.invalidateQueries({ queryKey: ['index', 'status'] });
    },
  });

  const handleAddRelationSaved = useCallback((result: MutationResultDto) => {
    setShowAddRelation(false);
    onMutationSuccess(result);
    queryClient.invalidateQueries({ queryKey: ['table', tableId] });
    queryClient.invalidateQueries({ queryKey: ['graph'] });
  }, [queryClient, tableId]);

  // ── 渲染分支 ──

  // 没选表时整个面板由 KnowledgePage 决定不渲染，这里不再画空态。
  if (!selectedNodeId) {
    return null;
  }

  if (isLoading) {
    return (
      <div className="table-inspector-loading">
        <div className="spinner" />
        <span>加载中…</span>
      </div>
    );
  }

  if (isError || !tableDetail) {
    return (
      <div className="table-inspector-error">
        <p>无法加载表详情</p>
        <Button onClick={() => refetch()} size="sm">
          重试
        </Button>
      </div>
    );
  }

  const { table, columns, inEdges, outEdges, validationIssues, recentChanges } = tableDetail;

  return (
    <div className="table-inspector">
      {/* ── 头部：表名 + 刷新 ── */}
      <div className="inspector-header">
        <h3 className="inspector-title">{table.qualifiedName}</h3>
        <Button
          title="在工作台查询：带着 SELECT * FROM 这张表 LIMIT 100 跳过去"
          aria-label="在工作台查询"
          disabled={!alias}
          onClick={() => {
            stashWorkbenchSql(selectSampleSql(table.schema, table.name));
            navigate(navPath('sql', alias));
          }}
          size="sm"
          icon
        >
          <QueryIcon />
        </Button>
        <Button
          title="从数据库重新读取字段、类型和注释；业务名、业务描述、语义类型和自建关系不会被覆盖"
          aria-label="刷新表结构"
          disabled={refresh.isPending || !alias}
          onClick={() => refresh.mutate({ schema: table.schema, table: table.name })}
          size="sm"
          icon
        >
          {refresh.isPending ? <span className="spinner" /> : <RefreshIcon />}
        </Button>
        <button className="inspector-close" onClick={() => selectNode(null)} title="关闭">
          &times;
        </button>
      </div>
      {refresh.isError && (
        <p className="inspector-alert" role="alert">刷新失败：{refresh.error.message}</p>
      )}

      {/* ── 概要 ── */}
      <div className="inspector-section inspector-summary">
        <div className="inspector-meta">
          <span><i>类型</i>{table.tableType || '—'}</span>
          <RowCount schema={table.schema} table={table.name} />
        </div>
        <div className="inspector-field">
          <span className="desc-editor-label">注释</span>
          <span className="inspector-readonly" title="来自数据库，刷新时同步">
            {table.comment || '—'}
          </span>
        </div>
        <DescriptionEditor
          label="业务名"
          value={table.businessName}
          placeholder="未填写业务名"
          multiline={false}
          onSave={async (newValue) => { await patchBusinessName.mutateAsync(newValue); }}
          readOnly={patchBusinessName.isPending}
        />
        {patchBusinessName.isError && (
          <div className="desc-editor-error">
            {(patchBusinessName.error as Error)?.message || '保存失败'}
          </div>
        )}
        <DescriptionEditor
          label="业务描述"
          value={table.description ?? undefined}
          placeholder="未填写业务描述"
          multiline={true}
          onSave={async (newValue) => { await patchDescription.mutateAsync(newValue); }}
          readOnly={patchDescription.isPending}
        />
        {patchDescription.isError && (
          <div className="desc-editor-error">
            {(patchDescription.error as Error)?.message || '保存失败'}
          </div>
        )}
      </div>

      {/* ── 字段 ── */}
      <div className="inspector-section">
        <h4 className="inspector-section-title">字段（{columns.length}）</h4>
        <div className="inspector-columns">
          <table className="columns-table">
            <thead>
              <tr>
                <th>字段名</th>
                <th>类型</th>
                <th>长度</th>
                <th>可空</th>
                <th>注释</th>
              </tr>
            </thead>
            <tbody>
              {columns.map((col) => (
                <ColumnRow
                  key={col.name}
                  tableId={tableId!}
                  tableQualifiedName={table.qualifiedName}
                  column={col}
                  revision={workspaceRevision}
                  onRefetch={() =>
                    queryClient.invalidateQueries({ queryKey: ['table', tableId] })
                  }
                />
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* ── 索引 ── */}
      <IndexSection indexes={table.indexes} primaryKey={table.primaryKey} />

      {/* ── 关系 ── */}
      <div className="inspector-section">
        <h4 className="inspector-section-title">关系</h4>

        <RelationList
          title="入向关系"
          relations={inEdges}
          direction="in"
          revision={workspaceRevision}
          onRefresh={() =>
            queryClient.invalidateQueries({ queryKey: ['table', tableId] })
          }
        />

        <RelationList
          title="出向关系"
          relations={outEdges}
          direction="out"
          revision={workspaceRevision}
          onRefresh={() =>
            queryClient.invalidateQueries({ queryKey: ['table', tableId] })
          }
        />

        {!showAddRelation ? (
          <ActionIcon action="add" label="新建关系" onClick={() => setShowAddRelation(true)} />
        ) : (
          <div className="add-relation-editor">
            <RelationEditor
              mode="create"
              revision={workspaceRevision}
              onSaved={handleAddRelationSaved}
              onCancel={() => setShowAddRelation(false)}
            />
          </div>
        )}

        {(inEdges.length === 0 && outEdges.length === 0 && !showAddRelation) && (
          <div className="relations-empty">这张表还没有任何关系。</div>
        )}
      </div>

      {/* ── 校验问题 ── */}
      {validationIssues.length > 0 && (
        <div className="inspector-section">
          <h4 className="inspector-section-title">校验问题（{validationIssues.length}）</h4>
          <ul className="validation-list">
            {validationIssues.map((issue) => (
              <li key={issue.id} className={`validation-item validation-${issue.severity}`}>
                <span className={`severity-badge severity-${issue.severity}`}>
                  {severityLabel(issue.severity)}
                </span>
                <span className="validation-message">{issue.message}</span>
                {issue.field && <span className="validation-field">{issue.field}</span>}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ── 最近变更 ── */}
      {recentChanges.length > 0 && (
        <details className="inspector-section">
          <summary className="inspector-section-title">最近变更（{recentChanges.length}）</summary>
          <ul className="recent-changes-list">
            {recentChanges.map((change) => (
              <li key={change.id}>
                <span className="change-operation">{change.operation}</span>
                <span className="change-actor">{change.actor}</span>
                <span className="change-timestamp">{change.timestamp}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  );
}

/**
 * 索引列表。
 *
 * 这是**数据库索引**，不是搜索索引——搜索索引是工作区级的，它的重建入口在图谱页工具条，
 * 两者别混（历史上 TableInspector 里放过搜索索引，作用域错位，已删）。
 *
 * 「类型」不是存出来的字段：`TableIndexMetadata` 只有 name / unique / columns，
 * 主键索引在各方言里名字不同（MySQL 叫 PRIMARY，Oracle/PG 是生成名），
 * 所以按字段组成是否等于主键来判断，比按名字匹配可靠。
 */
function IndexSection({ indexes, primaryKey }: { indexes?: TableIndexDto[]; primaryKey?: string[] }) {
  if (!indexes?.length) {
    return null;
  }
  // primaryKey 存的是列 id（column:alias:schema.table.COL），索引里是裸列名。
  // 直接比永远不相等，主键索引会被显示成「唯一」——实测踩到过。
  const pk = (primaryKey ?? [])
    .map((id) => id.slice(id.lastIndexOf('.') + 1))
    .join(',')
    .toLowerCase();
  return (
    <div className="inspector-section">
      <h4 className="inspector-section-title">索引（{indexes.length}）</h4>
      <table className="index-table">
        <thead>
          <tr>
            <th>类型</th>
            <th>索引名</th>
            <th>字段</th>
          </tr>
        </thead>
        <tbody>
          {indexes.map((index) => {
            const isPk = pk.length > 0 && index.columns.join(',').toLowerCase() === pk;
            const kind = isPk ? '主键' : index.unique ? '唯一' : '普通';
            return (
              <tr key={index.name}>
                <td>
                  <span className="index-kind" data-kind={isPk ? 'pk' : index.unique ? 'unique' : 'normal'}>
                    {kind}
                  </span>
                </td>
                <td className="index-name">{index.name}</td>
                {/* 复合索引的字段顺序决定最左前缀能不能用上，所以按原顺序展示、不排序 */}
                <td className="index-columns">{index.columns.join(', ')}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/**
 * 行数：默认不查，点刷新才走 COUNT(*)。
 *
 * 精确计数在大表上要几秒，打开表详情就自动跑会让每次点击都变慢，
 * 而多数时候并不需要这个数字。
 */
function RowCount({ schema, table }: { schema: string; table: string }) {
  const count = useMutation({
    mutationFn: () => getRowCount(schema, table),
  });

  return (
    <span>
      <i>行数</i>
      {count.data ? count.data.rowCount.toLocaleString() : count.isError ? '查询失败' : '未采集'}
      <Button
        title="实时统计当前行数（大表可能需要几秒）"
        aria-label="采集行数"
        disabled={count.isPending}
        onClick={() => count.mutate()}
        size="sm"
        icon
        className="inspector-inline-btn"
      >
        {count.isPending ? <span className="spinner" /> : <RefreshIcon />}
      </Button>
    </span>
  );
}

/**
 * 可空状态点：绿=可空，红=不可空，黄=未知。
 *
 * 与顶栏状态灯同一套配色规则（见 CLAUDE.md「状态色」），说明只放 title。
 */
function NullableDot({ nullable }: { nullable?: boolean | null }) {
  const tone = nullable == null ? 'warn' : nullable ? 'ok' : 'bad';
  const detail = nullable == null ? '可空性未知' : nullable ? '可空' : '不可空';
  return (
    <StatusDot tone={tone} label={detail} />
  );
}

function severityLabel(severity: string): string {
  switch (severity) {
    case 'error':
      return '错误';
    case 'warning':
      return '警告';
    case 'info':
      return '提示';
    default:
      return severity.toUpperCase();
  }
}
