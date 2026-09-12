import { Fragment, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  decideApproval, decideBatch, getApprovalDetail, getApprovals,
} from '../../api/approvals';
import { queryClient } from '../../api/queryClient';
import { Button } from '../../ui/Button';
import { FilterBar } from '../../ui/FilterBar';
import { Pagination } from '../../ui/Pagination';
import { Select } from '../../ui/Select';
import { StatusDot } from '../../ui/StatusDot';
import type {
  ApprovalBatchDto,
  ApprovalDto,
  ApprovalEventDto,
  ApprovalKind,
  ApprovalStatus,
  BatchStatus,
  PrecheckDto,
} from '../../types/api';
import { GraphDiff } from './GraphDiff';
import { TIME_RANGES, since } from './timeRange';

const KIND_LABEL: Record<ApprovalKind, string> = {
  query: '查询',
  update: '更新',
  graph: '图谱',
  recovery: '回滚',
};

const STATUS_LABEL: Record<ApprovalStatus, string> = {
  pending: '待审批',
  approved: '已批准',
  rejected: '已拒绝',
  expired: '已作废',
};

/** 绿=已批准，黄=待审批（可用但需处理），红=拒绝或作废。沿用 CLAUDE.md 的状态色。 */
const STATUS_TONE: Record<ApprovalStatus, 'ok' | 'warn' | 'bad'> = {
  pending: 'warn',
  approved: 'ok',
  rejected: 'bad',
  expired: 'bad',
};

const BATCH_STATUS_LABEL: Record<BatchStatus, string> = {
  draft: '未提交',
  pending: '待审批',
  applied: '已落地',
  partial: '部分落地',
  rejected: '已拒绝',
  failed: '落地失败',
  superseded: '已被取代',
};

/** 部分落地是黄：它成功了，但里面有被否掉的条目，提交方要去看理由。 */
const BATCH_STATUS_TONE: Record<BatchStatus, 'ok' | 'warn' | 'bad'> = {
  draft: 'warn',
  pending: 'warn',
  applied: 'ok',
  partial: 'warn',
  rejected: 'bad',
  failed: 'bad',
  superseded: 'bad',
};

/** 时间线上的一格叫什么。事件名是 Stage 名，直接显示没人看得懂。 */
const EVENT_LABEL: Record<string, string> = {
  submitted: '提交',
  guard: '校验',
  precheck: '预检',
  approval: '审批放行',
  cipher: '改写',
  dialect: '方言处理',
  executed: '执行完成',
  rejected: '已拒绝',
  failed: '执行失败',
};

/**
 * 批准之后实际发生了什么。
 *
 * 审批状态只说人批没批：query / update 类批准时什么都不做，执行在另一个进程里，
 * 结果从不写回审批记录。所以「已批准」旁边必须并排一句执行结果，否则一条批准后
 * 被拒的 SQL 在列表里和跑成功的长得一模一样。成功不显示——绿色的「已批准」已经说了。
 */
const TASK_OUTCOME: Record<string, string> = {
  rejected: '执行被拒',
  failed: '执行失败',
  running: '执行中',
};

function TaskOutcome({ item }: { item: ApprovalDto }) {
  const outcome = item.taskStatus ? TASK_OUTCOME[item.taskStatus] : null;
  if (item.status !== 'approved' || !outcome) return null;
  return (
    <span
      className="review-outcome"
      data-tone={item.taskStatus === 'running' ? 'warn' : 'bad'}
      title={`批准之后这条 SQL 的结果：${outcome}。命令行看原因：sql-cli task status ${item.taskRunId}`}
    >
      {outcome}
    </span>
  );
}

/** 详情事件里挑出预检结论；没有 precheck 事件就返回 null。 */
export function findPrecheck(events: ApprovalEventDto[]): PrecheckDto | null {
  const payload = events.find((event) => event.eventType === 'precheck')?.payload;
  return payload ? (payload as unknown as PrecheckDto) : null;
}

/**
 * 真正改了多少行。预检给的是「可能影响」，这个是执行完的实际值——
 * 两个数并排放才看得出批准时的判断准不准。还没执行完就返回 null。
 */
export function findAffectedRows(events: ApprovalEventDto[]): number | null {
  const payload = events.find((event) => event.eventType === 'executed')?.payload;
  return typeof payload?.affectedRows === 'number' ? payload.affectedRows : null;
}

/**
 * 审批列表。
 *
 * 待审批视图（`fixedStatus='pending'`）对面是一个正在等的进程——CLI 卡在终端里，
 * 或者 UI 的某个请求挂着——所以两秒轮询一次：批准之后对方最多两秒就动了，
 * 不需要人再刷新一次页面。审批记录是只读的历史，不轮询。
 *
 * 待审批跨数据源看（CLI 在别的库上等审批时，不该因为顶栏选中的是另一个别名就看不见）；
 * 审批记录是历史，跟着顶栏选中的数据源走。
 */
export function ApprovalList({
  fixedStatus, alias, excludeKind, excludeStatus, fixedKind, leading,
}: {
  fixedStatus?: ApprovalStatus;
  alias?: string | null;
  /** 排除这个类型。「审批记录」传 'graph'：图谱的历史在图谱标签里 */
  excludeKind?: ApprovalKind;
  /** 排除这个状态。「图谱」传 'pending'：还没裁决的在待审批标签里 */
  excludeStatus?: ApprovalStatus;
  /** 只看这个类型，锁死不给筛。「图谱」标签传 'graph' */
  fixedKind?: ApprovalKind;
  /** 塞进工具条最左边的额外筛选项。图谱标签用它放视图切换，免得再叠一条工具条 */
  leading?: React.ReactNode;
}) {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [kind, setKind] = useState<ApprovalKind | 'all'>('all');
  const [status, setStatus] = useState<ApprovalStatus | 'all'>('all');
  const [range, setRange] = useState('all');

  const effectiveStatus = fixedStatus ?? status;
  const effectiveKind = fixedKind ?? kind;
  const live = fixedStatus === 'pending';
  const filtered = (!fixedKind && kind !== 'all') || range !== 'all'
    || (!fixedStatus && status !== 'all');

  // 'all' = 不按数据源过滤，服务端认这个值
  const scope = live ? 'all' : alias ?? 'all';

  const list = useQuery({
    queryKey: ['approvals', effectiveStatus, excludeStatus, effectiveKind, excludeKind,
      range, scope, page, pageSize],
    queryFn: ({ signal }) =>
      getApprovals(
        {
          status: effectiveStatus,
          excludeStatus,
          kind: effectiveKind,
          excludeKind,
          alias: scope,
          createdAfter: since(range),
        },
        page,
        pageSize,
        signal,
      ),
    refetchInterval: live ? 2000 : false,
    placeholderData: (prev) => prev,
  });

  const items = list.data?.approvals ?? [];

  /** 改筛选条件要回到第一页，否则会停在一个新结果集里不存在的页码上。 */
  function change<T extends string>(setter: (value: T) => void) {
    return (event: React.ChangeEvent<HTMLSelectElement>) => {
      setter(event.target.value as T);
      setPage(0);
    };
  }

  const onStatus = change<ApprovalStatus | 'all'>(setStatus);
  const onKind = change<ApprovalKind | 'all'>(setKind);
  const onRange = change<string>(setRange);

  return (
    <>
      <FilterBar>
        {leading}
        {!fixedStatus && (
          <Select size="sm" value={status} onChange={onStatus} label="按状态筛选" title="按状态筛选">
            <option value="all">全部状态</option>
            {excludeStatus !== 'pending' && <option value="pending">待审批</option>}
            <option value="approved">已批准</option>
            <option value="rejected">已拒绝</option>
            <option value="expired">已作废</option>
          </Select>
        )}
        {!fixedKind && (
          <Select size="sm" value={kind} onChange={onKind} label="按类型筛选" title="按类型筛选">
            <option value="all">全部类型</option>
            <option value="query">查询</option>
            <option value="update">更新</option>
            {excludeKind !== 'graph' && <option value="graph">图谱</option>}
            <option value="recovery">回滚</option>
          </Select>
        )}
        <Select size="sm" value={range} onChange={onRange} label="按时间范围筛选" title="按时间范围筛选">
          {TIME_RANGES.map((item) => (
            <option key={item.key} value={item.key}>{item.label}</option>
          ))}
        </Select>
        {filtered && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setKind('all');
              setStatus('all');
              setRange('all');
              setPage(0);
            }}
          >
            清除筛选
          </Button>
        )}
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
        <p className="review-hint">
          {filtered
            ? '没有符合条件的记录。'
            : live
              ? '没有待审批的操作。要让操作停在这里等放行，去设置页给数据源打开查询 / 更新 / 图谱审批。'
              : '还没有审批记录。'}
        </p>
      )}

      <div className="review-list" data-stale={list.isPlaceholderData || undefined}>
        {groupByBatch(items, list.data?.batches ?? []).map((group) => (
          group.batch
            ? <BatchCard key={`batch-${group.batch.id}`} batch={group.batch} items={group.items} />
            : <ApprovalCard key={group.items[0].id} item={group.items[0]} />
        ))}
      </div>
    </>
  );
}

/**
 * 把一页条目按批次归拢：一批一张卡，无批次的仍然一条一张。
 *
 * 归拢在前端做而不是让服务端返回嵌套结构，是因为分页的单位是条目——服务端返回
 * 「批次树」就得再造一套按批分页，而一批 40 条本来就该在同一页里被一次批掉。
 *
 * ponytail: 一批跨页时那一页只显示它落在本页的部分。条目 id 连号、默认每页 20 条，
 * 实际很少撞上；真撞上了把每页调大即可，不为它加一套按批分页。
 */
export function groupByBatch(
  items: ApprovalDto[],
  batches: ApprovalBatchDto[],
): Array<{ batch: ApprovalBatchDto | null; items: ApprovalDto[] }> {
  const index = new Map(batches.map((batch) => [batch.id, batch]));
  const groups: Array<{ batch: ApprovalBatchDto | null; items: ApprovalDto[] }> = [];
  const seen = new Map<number, { batch: ApprovalBatchDto | null; items: ApprovalDto[] }>();
  for (const item of items) {
    const batch = item.batchId == null ? null : index.get(item.batchId) ?? null;
    if (!batch) {
      groups.push({ batch: null, items: [item] });
      continue;
    }
    const existing = seen.get(batch.id);
    if (existing) {
      existing.items.push(item);
    } else {
      const group = { batch, items: [item] };
      seen.set(batch.id, group);
      groups.push(group);
    }
  }
  for (const group of groups) {
    if (group.batch) group.items.sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));
  }
  return groups;
}

/**
 * 一批 = 一张卡。标题是 intent——人裁决的是那件事，不是里面的 40 个对象。
 *
 * 每条可以单独「否掉」，卡片底部「批准这一批」落地剩下的。允许部分批准：
 * 30 条错 2 条就整批打回、让提交方全部重来，代价太大。
 */
function BatchCard({ batch, items }: { batch: ApprovalBatchDto; items: ApprovalDto[] }) {
  const [reason, setReason] = useState('');
  const [open, setOpen] = useState(false);
  // 存「排除了哪几条」而不是「选中了哪几条」：条目会随着轮询变动，
  // 存选中集就要在每次刷新后跟数据对账，存排除集不用——没排除的天然是选中
  const [excluded, setExcluded] = useState<Set<number>>(new Set());
  const pending = items.filter((item) => item.status === 'pending');
  const decidable = batch.status === 'pending' && pending.length > 0;
  const willApprove = pending.filter((item) => !excluded.has(item.id));
  const willReject = pending.filter((item) => excluded.has(item.id));

  const decide = useMutation({
    mutationFn: async (decision: 'approved' | 'rejected') => {
      if (decision === 'approved') {
        // 取消勾选 = 否掉这条。服务端本来就支持部分批准：先逐条否，
        // 再批整批，剩下的 pending 一起落地
        for (const item of willReject) {
          await decideApproval(item.id, 'rejected', reason);
        }
      }
      return decideBatch(batch.id, decision, reason);
    },
    // 图谱批次批准会真的写图谱，所有图谱读缓存都过期了
    onSuccess: () => (batch.kind === 'graph'
      ? queryClient.invalidateQueries()
      : queryClient.invalidateQueries({ queryKey: ['approvals'] })),
  });

  const stamp = batch.submittedAt ?? batch.createdAt;

  return (
    <article className="review-card review-batch" data-tone={BATCH_STATUS_TONE[batch.status]}>
      <div className="review-card-top">
        <StatusDot tone={BATCH_STATUS_TONE[batch.status]} />
        <span className="review-kind">批次</span>
        <span className="review-id" title="批次编号；命令行取回结果：batch status">#{batch.id}</span>
        <span className="review-status">{BATCH_STATUS_LABEL[batch.status] ?? batch.status}</span>
        <span className="review-alias">{batch.alias}</span>
        <span className="review-kind">{batch.kind}</span>
        <time dateTime={new Date(stamp).toISOString()}>{new Date(stamp).toLocaleString()}</time>
      </div>

      {/* 折叠：一批四十条全摊开就没法看，人只会往下拉到底然后整批点同意——
          那正是批次要防的盲批。先给 intent 和条数，要看细节再点开 */}
      <button
        type="button"
        className="review-batch-toggle"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <span className="review-batch-caret" aria-hidden="true">{open ? '▾' : '▸'}</span>
        <span className="review-batch-intent">{batch.intent}</span>
        <span className="review-batch-count">{items.length} 条</span>
      </button>
      <p className="review-batch-meta">
        {items.length !== pending.length && <span>已否 {items.length - pending.length} 条</span>}
        <Provenance item={items[0]} />
        {!batch.recoverable && <span className="review-batch-warn">本批不可回滚（含 DDL）</span>}
      </p>

      {open && (
        <ol className="review-batch-items">
          {items.map((item) => (
            <BatchItem
              key={item.id}
              item={item}
              kind={batch.kind}
              checked={!excluded.has(item.id)}
              onToggle={() => setExcluded((previous) => {
                const next = new Set(previous);
                if (next.has(item.id)) next.delete(item.id);
                else next.add(item.id);
                return next;
              })}
            />
          ))}
        </ol>
      )}

      {decidable && (
        <div className="review-card-actions">
          <div className="review-decide">
            <input
              type="text"
              placeholder={willReject.length > 0 ? '理由（有条目被取消勾选，必填）' : '理由（拒绝必填）'}
              aria-label="审批理由"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
            <Button
              variant="primary"
              size="sm"
              disabled={decide.isPending || (willReject.length > 0 && !reason.trim())}
              title={batch.kind === 'graph'
                ? '一次写入，要么全进要么图谱一个字节没动'
                : '单连接单事务按序执行，任一条失败整批回滚'}
              onClick={() => decide.mutate('approved')}
            >
              {willReject.length > 0
                ? `批准 ${willApprove.length} 条，否掉 ${willReject.length} 条`
                : `批准这一批（${pending.length} 条）`}
            </Button>
            <Button
              variant="danger"
              size="sm"
              disabled={decide.isPending || !reason.trim()}
              title={reason.trim() ? '整批不落地' : '拒绝必须填写理由'}
              onClick={() => decide.mutate('rejected')}
            >
              否掉整批
            </Button>
          </div>
        </div>
      )}

      {batch.reason && <p className="review-reason">理由：{batch.reason}</p>}
      {decide.isError && <p className="review-alert" role="alert">{decide.error.message}</p>}
    </article>
  );
}

/** 批次里的一条：勾选 + 摘要 + 内容（图谱摊 diff，SQL 摊语句）+ 单独否掉。 */
function BatchItem({
  item,
  kind,
  checked,
  onToggle,
}: {
  item: ApprovalDto;
  kind: 'graph' | 'sql';
  checked: boolean;
  onToggle: () => void;
}) {
  const [reason, setReason] = useState('');
  const pending = item.status === 'pending';

  const reject = useMutation({
    mutationFn: () => decideApproval(item.id, 'rejected', reason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['approvals'] }),
  });

  return (
    <li className="review-batch-item" data-tone={STATUS_TONE[item.status]}>
      <div className="review-batch-item-top">
        {item.status === 'pending' && (
          <input
            type="checkbox"
            className="review-batch-check"
            checked={checked}
            onChange={onToggle}
            aria-label={`批准第 ${item.id} 条`}
            title="取消勾选 = 批准这一批时否掉这条"
          />
        )}
        <span className="review-id">#{item.id}</span>
        <code className="review-summary">{item.summary}</code>
        {!pending && (
          <span className="review-status">{STATUS_LABEL[item.status] ?? item.status}</span>
        )}
        <TaskOutcome item={item} />
      </div>
      {kind === 'graph' && item.payload && <GraphDiff payload={item.payload} />}
      {kind === 'sql' && item.detail && <pre className="review-detail">{item.detail}</pre>}
      {pending && (
        <div className="review-decide">
          <input
            type="text"
            placeholder="否掉这条的理由（必填）"
            aria-label="否掉这条的理由"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
          <Button
            variant="danger"
            size="sm"
            disabled={reject.isPending || !reason.trim()}
            title={reason.trim() ? '这一条不落地，其余照常' : '否掉必须填写理由'}
            onClick={() => reject.mutate()}
          >
            否掉这条
          </Button>
        </div>
      )}
      {!pending && item.reason && <p className="review-reason">理由：{item.reason}</p>}
      {reject.isError && <p className="review-alert" role="alert">{reject.error.message}</p>}
    </li>
  );
}

/** 溯源那一行：谁、哪次会话。两个都没有就整行不出现，别留一行「— · —」。 */
function Provenance({ item }: { item: ApprovalDto }) {
  if (!item.agentId && !item.sessionId) return null;
  return (
    <span className="review-provenance">
      {item.agentId && <span title="提交这条的 agent">{item.agentId}</span>}
      {item.sessionId && <span title="会话 id">会话 {item.sessionId}</span>}
    </span>
  );
}

function ApprovalCard({ item }: { item: ApprovalDto }) {
  const [reason, setReason] = useState('');
  const [open, setOpen] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const pending = item.status === 'pending';

  const graph = item.kind === 'graph';
  /** 候选边的发布审批：对象已经在图谱里，批准 = 发布，拒绝 = 转 ignored。 */
  const publishReview = graph && item.payload?.action === 'publish';

  const decide = useMutation({
    mutationFn: (decision: 'approved' | 'rejected') => decideApproval(item.id, decision, reason),
    // 批准一条图谱变更会真的写图谱，所有图谱读缓存都过期了；SQL 审批只动审批表。
    onSuccess: () => (graph
      ? queryClient.invalidateQueries()
      : queryClient.invalidateQueries({ queryKey: ['approvals'] })),
  });

  return (
    <article className="review-card" data-tone={STATUS_TONE[item.status]}>
      <div className="review-card-top">
        <StatusDot tone={STATUS_TONE[item.status]} />
        <span className="review-kind">{KIND_LABEL[item.kind] ?? item.kind}</span>
        {/*
          编号必须露出来：CLI 提交写操作时打印的是「审批 #12 / 任务 #7」，
          不显示的话人拿着那两个号在这一页里找不到对应的卡片，反过来在这一页看到的东西
          也没法拿去命令行查（`sql-cli task status <id>` 要的就是任务号）。
        */}
        <span className="review-id" title="审批编号">#{item.id}</span>
        {item.taskRunId != null && (
          <span className="review-id" title={`写任务编号，命令行查进度：sql-cli task status ${item.taskRunId}`}>
            任务 #{item.taskRunId}
          </span>
        )}
        <span className="review-status">{STATUS_LABEL[item.status] ?? item.status}</span>
        <TaskOutcome item={item} />
        <span className="review-alias">{item.alias}</span>
        <Provenance item={item} />
        <time dateTime={new Date(item.createdAt).toISOString()}>
          {new Date(item.createdAt).toLocaleString()}
        </time>
      </div>

      <code className="review-summary">{item.summary}</code>

      {/*
        图谱审批批的是一次内容变更，不看 before/after 批不下去，所以直接摊开而不是藏进「详情」。
        候选边的发布审批（action=publish）例外：对象已经在图谱里，没有 before/after 可比，
        该看的是端点、置信度、证据——摘要那行已经带了前三样，其余在「详情」里。
      */}
      {item.payload && item.payload.action !== 'publish' && <GraphDiff payload={item.payload} />}
      {graph && !item.payload && pending && (
        <p className="review-hint">
          这条审批没有记下变更内容（升级前提交的），批准不会改动图谱，请让提交方重新提交。
        </p>
      )}

      <div className="review-card-actions">
        {item.detail && item.detail !== item.summary && (
          <Button variant="ghost" size="sm" aria-expanded={open} onClick={() => setOpen(!open)}>
            {open ? '收起原文' : '查看原文'}
          </Button>
        )}
        <Button
          variant="ghost"
          size="sm"
          aria-expanded={detailOpen}
          title="预检结论与审计时间线"
          onClick={() => setDetailOpen(!detailOpen)}
        >
          {detailOpen ? '收起详情' : '详情'}
        </Button>

        {pending && (
          <div className="review-decide">
            <input
              type="text"
              placeholder="理由（拒绝必填）"
              aria-label="审批理由"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
            <Button
              variant="primary"
              size="sm"
              disabled={decide.isPending}
              title={publishReview ? '发布后这条边才算正式关系'
                : graph ? '批准后变更才写进图谱' : undefined}
              onClick={() => decide.mutate('approved')}
            >
              {publishReview ? '批准并发布' : graph ? '批准并写入' : '批准'}
            </Button>
            <Button
              variant="danger"
              size="sm"
              disabled={decide.isPending || !reason.trim()}
              title={reason.trim()
                ? (publishReview ? '这条边转为已忽略，不会被重新挖掘'
                  : graph ? '图谱不会被改动' : undefined)
                : '拒绝必须填写理由'}
              onClick={() => decide.mutate('rejected')}
            >
              拒绝
            </Button>
          </div>
        )}
      </div>

      {open && item.detail && <pre className="review-detail">{item.detail}</pre>}
      {detailOpen && <ApprovalDetail id={item.id} />}

      {!pending && item.reason && <p className="review-reason">理由：{item.reason}</p>}
      {decide.isError && (
        <p className="review-alert" role="alert">{decide.error.message}</p>
      )}
    </article>
  );
}

/** 展开后才拉：详情要读 task_event，比列表重，而且大多数卡片不会被展开。 */
function ApprovalDetail({ id }: { id: number }) {
  const detail = useQuery({
    queryKey: ['approval', id],
    queryFn: ({ signal }) => getApprovalDetail(id, signal),
  });

  if (detail.isPending) return <p className="review-hint">加载中…</p>;
  if (detail.isError) {
    return <p className="review-alert" role="alert">加载失败：{detail.error.message}</p>;
  }

  const events = detail.data.events ?? [];
  const precheck = findPrecheck(events);
  const affectedRows = findAffectedRows(events);
  const candidate = detail.data.graphCandidate;

  return (
    <div className="review-panel">
      {candidate && (
        <section>
          <h3>候选关系</h3>
          {!candidate.found ? (
            <p className="review-hint">{candidate.note ?? '候选已不在图谱中'}</p>
          ) : (
            <>
              <dl className="review-facts">
                <dt>变更</dt>
                <dd>{candidate.changeType === 'create' ? '新增（待发布）' : '已发布'}</dd>
                {candidate.fields && Object.entries(candidate.fields).map(([key, value]) => (
                  value == null ? null : (
                    <Fragment key={key}>
                      <dt>{key}</dt>
                      <dd>{String(value)}</dd>
                    </Fragment>
                  )
                ))}
              </dl>
              <h4>证据</h4>
              {!candidate.evidence?.length ? (
                <p className="review-hint">没有记录证据来源</p>
              ) : (
                <ul className="review-evidence">
                  {candidate.evidence.map((item, index) => (
                    <li key={index}>
                      {item.sourceType} · {item.sourceRef}
                      {item.observedAt && <time>{new Date(item.observedAt).toLocaleString()}</time>}
                    </li>
                  ))}
                </ul>
              )}
              <h4>校验结果</h4>
              {!candidate.validation?.length ? (
                <p className="review-hint">没有校验问题</p>
              ) : (
                <ul className="review-evidence">
                  {candidate.validation.map((issue, index) => (
                    <li key={index}>
                      <span className={`severity-badge severity-${issue.severity ?? 'info'}`}>
                        {issue.severity ?? 'info'}
                      </span>
                      {issue.message}
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </section>
      )}

      <section>
        <h3>预检</h3>
        {!precheck ? (
          <p className="review-hint">
            未采集预检（直执行路径或读操作）
            {affectedRows != null && `，实际影响 ${affectedRows} 行`}
          </p>
        ) : (
          <dl className="review-facts">
            <dt>目标表</dt>
            <dd>{precheck.table ?? '未知'}</dd>
            <dt>预估影响行数</dt>
            <dd>{precheck.estimatedRows ?? '未知'}</dd>
            <dt>实际影响行数</dt>
            <dd>{affectedRows ?? '尚未执行'}</dd>
            <dt>恢复 SQL</dt>
            <dd>
              <StatusDot tone={precheck.recoverySupported ? 'ok' : 'bad'} />
              {precheck.recoverySupported ? '可生成' : '不可生成'}
            </dd>
            <dt>主键</dt>
            <dd>{precheck.primaryKey?.length ? precheck.primaryKey.join('、') : '无'}</dd>
            {precheck.skippedReason && (
              <>
                <dt>跳过原因</dt>
                <dd>{precheck.skippedReason}</dd>
              </>
            )}
          </dl>
        )}
      </section>

      <section>
        <h3>时间线</h3>
        {events.length === 0 ? (
          <p className="review-hint">没有审计事件</p>
        ) : (
          <ol className="review-timeline">
            {events.map((event, index) => (
              <li key={`${event.eventType}-${index}`}>
                <span>{EVENT_LABEL[event.eventType] ?? event.eventType}</span>
                {event.eventType === 'executed'
                  && typeof event.payload?.affectedRows === 'number' && (
                    <em>影响 {event.payload.affectedRows} 行</em>
                  )}
                {typeof event.payload?.reason === 'string' && event.payload.reason && (
                  <em>{event.payload.reason}</em>
                )}
                <time dateTime={new Date(event.createdAt).toISOString()}>
                  {new Date(event.createdAt).toLocaleTimeString()}
                </time>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}
