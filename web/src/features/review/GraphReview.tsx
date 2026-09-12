import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listGraphChanges } from '../../api/graphChanges';
import { listRelations, unignoreRelation } from '../../api/relations';
import { onMutationSuccess } from '../../api/mutationHelpers';
import { queryClient } from '../../api/queryClient';
import { useSessionStore } from '../../state/sessionStore';
import { Button } from '../../ui/Button';
import { FilterBar } from '../../ui/FilterBar';
import { Pagination } from '../../ui/Pagination';
import { Select } from '../../ui/Select';
import { StatusDot } from '../../ui/StatusDot';
import { RelationTypeBadge } from '../relation-editor/RelationTypeBadge';
import { ApprovalList } from './ApprovalList';
import { GraphDiff, objectLabel } from './GraphDiff';
import type { GraphChangeDto, RelationEdgeDto } from '../../types/api';

/** 端点 ID 只留最后两段：`column:demo:APP.ORDERS.USER_ID` → `ORDERS.USER_ID`。 */
function endpointSummary(id: string): string {
  const last = id.split(':').pop() ?? id;
  const dots = last.split('.');
  return dots.length >= 3 ? dots.slice(-2).join('.') : last;
}

const VIEWS = [
  { key: 'approvals', label: '审批记录' },
  { key: 'log', label: '变更记录' },
  { key: 'ignored', label: '已拒绝关系' },
] as const;

type View = (typeof VIEWS)[number]['key'];

/**
 * 图谱这一侧的历史与队列。
 *
 * **待裁决的图谱变更不在这里**，它跟 SQL 审批一起排在「待审批」标签——
 * 一件等着我决定的事只该有一个入口，按类型拆成两个队列的结果是两边都得看一遍。
 * 这一页回答的是「已经发生过什么」：
 *
 * - **审批记录**——裁决过的图谱审批，带当时批的那份 before/after。
 * - **变更记录**——每次图谱更新一条，谁改的、改成了什么、哪条审批放行的。
 *   没开审批的别名直接生效的改动也在这里，所以它比审批记录全。
 * - **已拒绝关系**——被拒过的候选边，转成了 ignored 不再被重新挖掘；「撤销」把它放回
 *   候选队列，也就是重新排一条待审批。这是反悔用的，不是待办。
 *
 * **候选关系不在这里**：agent 写入的候选边现在同样会排一条待审批，跟其它待办一起
 * 在「待审批」标签里发布或拒绝。它曾经在这一页单开一个队列，那就是一件事两个入口。
 *
 * ponytail: 已拒绝那个视图在服务端整份返回、这里切页。它是人工评审的量级，
 * 真到翻不动的规模，该做的是按 schema 收窄而不是给那个端点加 offset。
 */
export function GraphReview({ alias }: { alias?: string | null }) {
  const [view, setView] = useState<View>('approvals');

  return (
    <>
      {view === 'approvals' && (
        // pending 排掉：还没裁决的图谱变更在「待审批」标签里
        <ApprovalList
          alias={alias}
          fixedKind="graph"
          excludeStatus="pending"
          leading={<ViewSelect value={view} onChange={setView} />}
        />
      )}
      {view === 'ignored' && <RelationQueue view={view} onView={setView} />}
      {view === 'log' && <ChangeLog alias={alias} view={view} onView={setView} />}
    </>
  );
}

/** 四个视图共用的切换器，放在工具条最左边。 */
function ViewSelect({ value, onChange }: { value: View; onChange: (view: View) => void }) {
  return (
    <Select
      size="sm"
      value={value}
      label="按内容筛选"
      title="按内容筛选"
      onChange={(event) => onChange(event.target.value as View)}
    >
      {VIEWS.map((item) => (
        <option key={item.key} value={item.key}>{item.label}</option>
      ))}
    </Select>
  );
}

/**
 * 被拒过的候选边。转成了 {@code ignored} 就不会被重新挖掘；「撤销」放回候选队列，
 * 也就是重新排一条待审批。这是反悔用的，不是待办——待办在「待审批」标签里。
 */
function RelationQueue({ view, onView }: { view: View; onView: (view: View) => void }) {
  const revision = useSessionStore((state) => state.revision);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const list = useQuery({
    queryKey: ['relations', 'review'],
    queryFn: ({ signal }) => listRelations('ignored', signal),
  });

  const items = useMemo(() => list.data?.relations ?? [], [list.data]);
  const shown = items.slice(page * pageSize, page * pageSize + pageSize);

  async function unignore(relationId: string) {
    setBusy(true);
    setError(null);
    try {
      onMutationSuccess(await unignoreRelation(relationId, revision, '撤销忽略'));
      await queryClient.invalidateQueries({ queryKey: ['relations', 'review'] });
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '撤销失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <FilterBar>
        <ViewSelect
          value={view}
          onChange={(next) => {
            onView(next);
            setPage(0);
          }}
        />
        <Pagination
          page={page}
          pageSize={pageSize}
          total={items.length}
          onPage={setPage}
          onPageSize={(size) => {
            setPageSize(size);
            setPage(0);
          }}
        />
      </FilterBar>

      {list.isPending && <p className="review-hint">加载中…</p>}
      {list.isError && (
        <p className="review-alert" role="alert">加载失败：{list.error.message}</p>
      )}
      {error && <p className="review-alert" role="alert">{error}</p>}
      {list.isSuccess && items.length === 0 && (
        <p className="review-hint">没有被拒绝的关系。</p>
      )}

      <div className="review-list">
        {shown.map((rel) => (
          <RelationCard
            key={rel.id}
            relation={rel}
            busy={busy}
            onUnignore={() => unignore(rel.id)}
          />
        ))}
      </div>
    </>
  );
}

function RelationCard({ relation, busy, onUnignore }: {
  relation: RelationEdgeDto;
  busy: boolean;
  onUnignore: () => void;
}) {
  const reason = relation.attributes?.rejectionReason;

  return (
    <article className="review-card" data-tone="bad">
      <div className="review-rel">
        <StatusDot tone="bad" />
        <RelationTypeBadge type={relation.type} size="sm" />
        <span className="review-rel-endpoint" title={relation.from}>
          {endpointSummary(relation.from)}
        </span>
        <span className="review-rel-arrow" aria-hidden="true">→</span>
        <span className="review-rel-endpoint" title={relation.to}>
          {endpointSummary(relation.to)}
        </span>
        {relation.confidence != null && (
          <span className="review-rel-meta" title="置信度">
            {Math.round(relation.confidence * 100)}%
          </span>
        )}
        <span className="review-rel-spacer" />
        <Button
          variant="ghost"
          size="sm"
          disabled={busy}
          title="放回候选队列，重新排一条待审批"
          onClick={onUnignore}
        >
          撤销
        </Button>
      </div>

      {relation.joinExpression && (
        <code className="review-summary">{relation.joinExpression}</code>
      )}
      {typeof reason === 'string' && <p className="review-reason">拒绝原因：{reason}</p>}
    </article>
  );
}

/** 变更记录：每次图谱更新一条，可追溯。 */
function ChangeLog({ alias, view, onView }: {
  alias?: string | null;
  view: View;
  onView: (view: View) => void;
}) {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  const list = useQuery({
    queryKey: ['graph-changes', alias, page, pageSize],
    queryFn: ({ signal }) => listGraphChanges({ alias }, page, pageSize, signal),
    placeholderData: (prev) => prev,
  });
  const items = list.data?.changes ?? [];

  return (
    <>
      <FilterBar>
        <ViewSelect
          value={view}
          onChange={(next) => {
            onView(next);
            setPage(0);
          }}
        />
        <Pagination
          page={page}
          pageSize={pageSize}
          total={list.data?.total ?? 0}
          onPage={setPage}
          onPageSize={(size) => {
            setPageSize(size);
            setPage(0);
          }}
        />
      </FilterBar>

      {list.isPending && <p className="review-hint">加载中…</p>}
      {list.isError && (
        <p className="review-alert" role="alert">加载失败：{list.error.message}</p>
      )}
      {list.isSuccess && items.length === 0 && (
        <p className="review-hint">还没有图谱变更记录。</p>
      )}

      <div className="review-list" data-stale={list.isPlaceholderData || undefined}>
        {items.map((change) => (
          <ChangeCard key={change.id} change={change} />
        ))}
      </div>
    </>
  );
}

function ChangeCard({ change }: { change: GraphChangeDto }) {
  const [open, setOpen] = useState(false);

  return (
    <article className="review-card" data-tone="ok">
      <div className="review-card-top">
        <StatusDot tone="ok" />
        <span className="review-kind">{change.operation}</span>
        <span className="review-alias">{change.alias}</span>
        {change.actor && <span className="review-rel-meta">{change.actor}</span>}
        <span className="review-rel-meta" title="图谱版本">
          r{change.revisionBefore} → r{change.revisionAfter}
        </span>
        {change.approvalId != null && (
          <span className="review-rel-meta" title="放行这次更新的审批">
            审批 #{change.approvalId}
          </span>
        )}
        <time dateTime={new Date(change.createdAt).toISOString()}>
          {new Date(change.createdAt).toLocaleString()}
        </time>
      </div>

      <code className="review-summary" title={change.targetId ?? undefined}>
        {objectLabel(change.targetId)}
      </code>
      {change.reason && <p className="review-reason">原因：{change.reason}</p>}

      {change.payload && (
        <div className="review-card-actions">
          <Button variant="ghost" size="sm" aria-expanded={open} onClick={() => setOpen(!open)}>
            {open ? '收起内容' : '查看内容'}
          </Button>
        </div>
      )}
      {open && change.payload && <GraphDiff payload={change.payload} />}
    </article>
  );
}
