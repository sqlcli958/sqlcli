import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import { ApprovalList } from './ApprovalList';
import type { ApprovalBatchDto, ApprovalDto } from '../../types/api';

vi.mock('../../api/approvals', async () => {
  const actual = await vi.importActual<typeof import('../../api/approvals')>('../../api/approvals');
  return {
    ...actual,
    getApprovals: vi.fn(),
    getApprovalDetail: vi.fn().mockResolvedValue({ events: [] }),
    decideApproval: vi.fn(),
    decideBatch: vi.fn(),
  };
});

const { getApprovals, decideApproval, decideBatch } = await import('../../api/approvals');

const batch: ApprovalBatchDto = {
  id: 7,
  alias: 'demo',
  kind: 'graph',
  intent: '梳理报事域状态字段',
  status: 'pending',
  recoverable: true,
  createdAt: 1_700_000_000_000,
  submittedAt: 1_700_000_000_000,
};

function item(id: number): ApprovalDto {
  return {
    id,
    alias: 'demo',
    kind: 'graph',
    summary: `schema edit #${id}`,
    status: 'pending',
    createdAt: 1_700_000_000_000,
    batchId: 7,
  };
}

function renderList() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ApprovalList fixedStatus="pending" />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.mocked(getApprovals).mockResolvedValue({
    approvals: [item(1), item(2), item(3)],
    batches: [batch],
    total: 3,
    pending: 3,
  });
  vi.mocked(decideApproval).mockResolvedValue(undefined as never);
  vi.mocked(decideBatch).mockResolvedValue(undefined as never);
});

test('批次默认折叠，只给 intent 和条数', async () => {
  renderList();
  await screen.findByText('梳理报事域状态字段');

  // 一批四十条全摊开就没法看，人只会拉到底整批点同意——那正是批次要防的盲批
  expect(screen.getByText('3 条')).toBeTruthy();
  expect(screen.queryByText('schema edit #1')).toBeNull();
});

test('点开才出条目', async () => {
  renderList();
  await userEvent.click(await screen.findByRole('button', { expanded: false }));

  expect(await screen.findByText('schema edit #1')).toBeTruthy();
  expect(screen.getByText('schema edit #3')).toBeTruthy();
});

test('取消勾选的条目在批准时被单独否掉，其余整批落地', async () => {
  renderList();
  await userEvent.click(await screen.findByRole('button', { expanded: false }));
  await userEvent.click(await screen.findByRole('checkbox', { name: '批准第 2 条' }));

  // 取消勾选也是「否」，所以理由变成必填
  const approve = screen.getByRole('button', { name: /批准 2 条，否掉 1 条/ });
  expect(approve).toBeDisabled();

  await userEvent.type(screen.getByLabelText('审批理由'), '这条重复了');
  await userEvent.click(approve);

  await waitFor(() => expect(decideApproval).toHaveBeenCalledWith(2, 'rejected', '这条重复了'));
  await waitFor(() => expect(decideBatch).toHaveBeenCalledWith(7, 'approved', '这条重复了'));
  // 只否被取消勾选的那一条，别把整批逐条否了
  expect(vi.mocked(decideApproval).mock.calls.length).toBe(1);
});

test('全部勾选时按钮回到整批文案，理由不必填', async () => {
  renderList();
  await userEvent.click(await screen.findByRole('button', { expanded: false }));

  const approve = screen.getByRole('button', { name: /批准这一批（3 条）/ });
  expect(approve).not.toBeDisabled();
});
