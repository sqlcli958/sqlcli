import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import { GraphReview } from './GraphReview';
import type { ApprovalDto, RelationEdgeDto } from '../../types/api';

vi.mock('../../api/relations', () => ({
  listRelations: vi.fn(),
  publishRelation: vi.fn(),
  rejectRelation: vi.fn(),
  unignoreRelation: vi.fn(),
  batchReviewRelations: vi.fn(),
}));
vi.mock('../../api/approvals', () => ({
  getApprovals: vi.fn(),
  getApprovalDetail: vi.fn(),
  decideApproval: vi.fn(),
}));
vi.mock('../../api/graphChanges', () => ({ listGraphChanges: vi.fn() }));

const relations = await import('../../api/relations');
const approvals = await import('../../api/approvals');
const graphChanges = await import('../../api/graphChanges');

const IGNORED_ID = 'relation:demo:join_observed:orders.shop_id->shops.id';
const COLUMN_ID = 'column:demo:APP.ORDERS.USER_ID';

function decidedChange(): ApprovalDto {
  return {
    id: 7,
    alias: 'demo',
    kind: 'graph',
    summary: 'schema edit',
    status: 'approved',
    reason: '描述准确',
    createdAt: 1_700_000_000_000,
    decidedAt: 1_700_000_050_000,
    targetId: COLUMN_ID,
    payload: {
      targetId: COLUMN_ID,
      operation: 'upsert_column',
      actor: 'agent',
      baseRevision: 5,
      before: { description: '旧描述', businessName: '用户' },
      after: { description: '下单用户 ID', businessName: '用户' },
    },
  };
}

function ignored(): RelationEdgeDto {
  return {
    id: IGNORED_ID,
    kind: 'relation',
    status: 'ignored',
    type: 'join_observed',
    from: 'column:demo:app.orders.shop_id',
    to: 'column:demo:app.shops.id',
    attributes: { rejectionReason: '字段命中率过低，误判' },
  };
}

function renderReview() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <GraphReview alias="demo" />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.mocked(approvals.getApprovals).mockResolvedValue({
    approvals: [decidedChange()], total: 1, pending: 0,
  });
  vi.mocked(approvals.decideApproval).mockResolvedValue(decidedChange());
  vi.mocked(relations.listRelations).mockResolvedValue({ relations: [ignored()], revision: 5 });
  vi.mocked(relations.unignoreRelation).mockResolvedValue({ newRevision: 6, changeId: 'c6' });
  vi.mocked(graphChanges.listGraphChanges).mockResolvedValue({
    changes: [{
      id: 3,
      alias: 'demo',
      operation: 'upsert_column',
      targetId: COLUMN_ID,
      actor: 'human',
      revisionBefore: 5,
      revisionAfter: 6,
      createdAt: 1_700_000_100_000,
      approvalId: 7,
      reason: 'schema edit',
      payload: decidedChange().payload,
    }],
    total: 1,
  });
});

// 待裁决的图谱变更在「待审批」标签里，这一页是历史——两边都列就成了两个入口。
test('默认列裁决过的图谱审批，排掉待审批的', async () => {
  renderReview();
  expect(await screen.findByText('APP.ORDERS.USER_ID')).toBeTruthy();

  const calls = vi.mocked(approvals.getApprovals).mock.calls;
  const call = calls[calls.length - 1];
  expect(call?.[0]?.kind).toBe('graph');
  expect(call?.[0]?.excludeStatus).toBe('pending');
  // 历史条目不给裁决按钮
  expect(screen.queryByRole('button', { name: '批准并写入' })).toBeNull();
  expect(document.querySelector('.review-status')?.textContent).toBe('已批准');
});

// 只列真正变了的字段：整份对象摊开的话，一次改描述会摆出一堆没变的行，
// 而评审要看的恰恰是变了的那一行。
test('审批记录里带着当时批的那份 before/after', async () => {
  renderReview();
  await screen.findByText('APP.ORDERS.USER_ID');

  expect(screen.getByText('旧描述')).toBeTruthy();
  expect(screen.getByText('下单用户 ID')).toBeTruthy();
  // businessName 前后一样，不该出现在 diff 里
  expect(screen.queryByText('businessName')).toBeNull();
  expect(screen.getByText('description')).toBeTruthy();
});

// 候选关系的发布 / 拒绝搬去「待审批」了——一件等着我决定的事只有一个入口。
// 这一页只剩「反悔」：把拒过的边放回候选队列，也就是重新排一条待审批。
test('已拒绝关系能读到原因并撤销，没有发布 / 批量入口', async () => {
  renderReview();
  await screen.findByText('APP.ORDERS.USER_ID');

  await userEvent.selectOptions(screen.getByLabelText('按内容筛选'), 'ignored');
  expect(await screen.findByText(/字段命中率过低/)).toBeTruthy();
  for (const label of ['发布', '批量发布', '批量拒绝', '全选本页']) {
    expect(screen.queryByRole('button', { name: label })).toBeNull();
  }

  await userEvent.click(screen.getByRole('button', { name: '撤销' }));
  await waitFor(() => expect(relations.unignoreRelation).toHaveBeenCalledWith(
    IGNORED_ID, expect.any(Number), '撤销忽略'));
});

// 「每个图谱更新都要可追溯」：谁改的、改成了什么、哪条审批放行的。
test('变更记录带 revision、审批号和变更内容', async () => {
  renderReview();
  await screen.findByText('APP.ORDERS.USER_ID');
  await userEvent.selectOptions(screen.getByLabelText('按内容筛选'), 'log');

  expect(await screen.findByText('r5 → r6')).toBeTruthy();
  expect(screen.getByText('审批 #7')).toBeTruthy();
  expect(screen.getByText('human')).toBeTruthy();

  await userEvent.click(screen.getByRole('button', { name: '查看内容' }));
  expect(await screen.findByText('下单用户 ID')).toBeTruthy();
});
