import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { getApprovals } from '../../api/approvals';
import { Tabs } from '../../ui/Tabs';
import { ApprovalList } from './ApprovalList';
import { ExecutionLog } from './ExecutionLog';
import { GraphReview } from './GraphReview';
import './review.css';

/**
 * 四个视图。前两个是「还有什么等着我」，后两个是「已经发生过什么」。
 *
 * 图谱单独一个标签而不是混进待审批：SQL 审批批的是一次执行（放不放行），
 * 图谱审批批的是一次内容变更——要看 before/after 才批得下去，卡片和动作都不一样。
 * 「待审批」因此排掉 graph（`excludeKind`），两个标签的角标加起来不重不漏。
 */
const VIEWS = [
  { key: 'pending', label: '待审批' },
  { key: 'history', label: '审批记录' },
  { key: 'graph', label: '图谱' },
  { key: 'executions', label: '执行记录' },
] as const;

type View = (typeof VIEWS)[number]['key'];

function isView(value: string | null): value is View {
  return VIEWS.some((item) => item.key === value);
}

/**
 * 评审页的外壳：标题、通栏标签、待办角标。列表本身各自实现，
 * 但共用同一套骨架——工具条（`FilterBar`，右端是 `Pagination`）、内容。
 *
 * 执行记录也在这一页：审批看「要不要放行」，执行记录看「放行之后发生了什么」，
 * 拆到两个页面就得来回跳。工作台不再重复展示它。
 */
export function ReviewsPage() {
  const [searchParams] = useSearchParams();
  const alias = searchParams.get('alias');
  // 图谱页的「去评审」链接带 ?tab=graph 过来，直接落在对的标签上
  const initial = searchParams.get('tab');
  const [view, setView] = useState<View>(isView(initial) ? initial : 'pending');

  // 角标独立于当前视图：在别的标签上也要看得见「还有几件事等着我」。
  // 只取一条，要的是响应里的 pending 计数而不是列表本身。
  const badge = useQuery({
    queryKey: ['approvals', 'pending-count'],
    queryFn: ({ signal }) => getApprovals({ status: 'pending' }, 0, 1, signal),
    refetchInterval: 2000,
  });

  const counts: Partial<Record<View, number>> = {
    pending: badge.data?.pending ?? 0,
  };

  return (
    <div className="page review">
      <header className="review-head">
        <h1>评审</h1>
        <Tabs
          label="评审视图"
          items={VIEWS.map((item) => ({ ...item, badge: counts[item.key] }))}
          value={view}
          onChange={setView}
        />
      </header>

      {view === 'executions' && <ExecutionLog alias={alias} />}
      {view === 'graph' && <GraphReview alias={alias} />}
      {(view === 'pending' || view === 'history') && (
        <ApprovalList
          key={view}
          fixedStatus={view === 'pending' ? 'pending' : undefined}
          // 待审批不拆类型；审批记录排掉图谱——图谱的历史在图谱标签里，还带变更流水
          excludeKind={view === 'history' ? 'graph' : undefined}
          alias={alias}
        />
      )}
    </div>
  );
}
