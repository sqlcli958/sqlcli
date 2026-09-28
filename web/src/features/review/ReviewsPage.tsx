import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { getApprovals } from '../../api/approvals';
import { Tabs } from '../../ui/Tabs';
import { ApprovalList } from './ApprovalList';
import { ExecutionLog } from './ExecutionLog';
import './review.css';

/**
 * 三个视图：待办、裁决历史、实际执行流水。
 *
 * 图谱不再单开顶层标签：待裁决的图谱变更跟其它审批一起在「待审批」，
 * 已裁决记录在「审批记录」，真正落地的图谱变更则和 SQL 一起进入「执行记录」。
 */
const VIEWS = [
  { key: 'pending', label: '待审批' },
  { key: 'history', label: '审批记录' },
  { key: 'executions', label: '执行记录' },
] as const;

type View = (typeof VIEWS)[number]['key'];

function resolveView(value: string | null): View {
  // 兼容旧链接 / 收藏：原 ?tab=graph 已合并进执行记录。
  if (value === 'graph') return 'executions';
  return VIEWS.some((item) => item.key === value) ? value as View : 'pending';
}

/**
 * 评审页的外壳：标题、通栏标签、待办角标。列表本身各自实现，
 * 但共用同一套骨架——工具条（`FilterBar`，右端是 `Pagination`）、内容。
 *
 * 执行记录也在这一页：审批看「要不要放行」，执行记录看「放行之后发生了什么」，
 * 拆到两个页面就得来回跳。工作台不再重复展示它。
 */
export function ReviewsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const alias = searchParams.get('alias');
  // tab 是评审链路的可导航状态；旧的 graph 链接自动落到已经合并后的执行记录。
  const requestedView = searchParams.get('tab');
  const view = resolveView(requestedView);

  const setView = (next: View) => {
    const params = new URLSearchParams(searchParams);
    if (next === 'pending') params.delete('tab');
    else params.set('tab', next);
    setSearchParams(params, { replace: true });
  };

  // 角标独立于当前视图：在别的标签上也要看得见「还有几件事等着我」。
  // 只取一条，要的是响应里的 pending 计数而不是列表本身。
  const badge = useQuery({
    queryKey: ['approvals', 'pending-count'],
    queryFn: ({ signal }) => getApprovals({ status: 'pending' }, 0, 1, signal),
    refetchInterval: 2000,
  });

  const pendingCount = badge.data?.pending ?? 0;
  const counts: Partial<Record<View, number>> = {
    pending: pendingCount,
  };

  return (
    <div className="page review">
      <header className="review-head review-head-v2">
        <div className="review-title-row">
          <div className="review-title-copy">
            <h1>评审</h1>
            <p>把高风险 SQL、图谱变更和执行结果放在同一个控制面里：先看影响，再放行，最后保留可追溯记录。</p>
          </div>
          <div className="review-overview" aria-label="评审概览">
            <div className="review-overview-item">
              <b>{pendingCount}</b>
              <span>待审批</span>
            </div>
            <div className="review-overview-item">
              <b title={alias ?? '全部数据源'}>{alias ?? '全部'}</b>
              <span>当前数据源</span>
            </div>
          </div>
        </div>
        <Tabs
          label="评审视图"
          items={VIEWS.map((item) => ({ ...item, badge: counts[item.key] }))}
          value={view}
          onChange={(next) => setView(next as View)}
        />
      </header>

      <section className="review-stage" data-view={view} aria-label={`${VIEWS.find((item) => item.key === view)?.label ?? '评审'}内容`}>
        {view === 'executions' && <ExecutionLog alias={alias} />}
        {(view === 'pending' || view === 'history') && (
          <ApprovalList
            key={view}
            fixedStatus={view === 'pending' ? 'pending' : undefined}
            // 图谱不再单开历史标签：审批记录保留所有类型，已落地变更另在执行记录追溯。
            alias={alias}
          />
        )}
      </section>
    </div>
  );
}
