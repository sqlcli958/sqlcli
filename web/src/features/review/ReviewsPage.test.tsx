import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import { ReviewsPage } from './ReviewsPage';
import { findPrecheck } from './ApprovalList';
import type { ApprovalDto } from '../../types/api';

vi.mock('../../api/approvals', () => ({
  getApprovals: vi.fn(),
  getApprovalDetail: vi.fn(),
  decideApproval: vi.fn(),
}));

vi.mock('../../api/relations', () => ({
  listRelations: vi.fn(),
  publishRelation: vi.fn(),
  rejectRelation: vi.fn(),
  unignoreRelation: vi.fn(),
  batchReviewRelations: vi.fn(),
}));

vi.mock('../../api/graphChanges', () => ({ listGraphChanges: vi.fn() }));

const { getApprovals, getApprovalDetail } = await import('../../api/approvals');
const { listRelations } = await import('../../api/relations');
const { listGraphChanges } = await import('../../api/graphChanges');

function approval(id: number, kind: ApprovalDto['kind']): ApprovalDto {
  return {
    id,
    alias: 'demo',
    kind,
    summary: `SELECT ${id}`,
    status: 'pending',
    createdAt: 1_700_000_000_000,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // 页面现在从 ?alias= 取数据源（执行记录标签要用），所以得有 Router
  return render(
    <MemoryRouter initialEntries={['/workspaces/local/reviews?alias=demo']}>
      <QueryClientProvider client={client}>
        <ReviewsPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.mocked(getApprovals).mockResolvedValue({
    approvals: [approval(1, 'update'), approval(2, 'graph')],
    total: 2,
    pending: 2,
  });
  vi.mocked(listRelations).mockResolvedValue({ relations: [], revision: 5 });
  vi.mocked(listGraphChanges).mockResolvedValue({ changes: [], total: 0 });
});

test('没有 precheck 事件时取不到预检', () => {
  expect(findPrecheck([{ eventType: 'submitted', createdAt: 1 }])).toBeNull();
  expect(
    findPrecheck([
      { eventType: 'submitted', createdAt: 1 },
      {
        eventType: 'precheck',
        createdAt: 2,
        payload: { table: 'users', recoverySupported: true, primaryKey: ['id'], estimatedRows: 7 },
      },
    ])?.table,
  ).toBe('users');
});

// 类型筛选走服务端而不是前端过滤一页数据：分页之后「当前页里符合类型的那几条」
// 和「符合类型的第 N 页」是两回事。
test('筛选条件带进请求，并回到第一页', async () => {
  renderPage();
  await screen.findByText('SELECT 1');

  await userEvent.selectOptions(screen.getByLabelText('按类型筛选'), 'update');
  await userEvent.selectOptions(screen.getByLabelText('按时间范围筛选'), '7d');

  await waitFor(() => {
    const calls = vi.mocked(getApprovals).mock.calls;
    const [filters, page] = calls[calls.length - 1];
    expect(filters?.kind).toBe('update');
    expect(filters?.status).toBe('pending');
    expect(filters?.createdAfter).toBeGreaterThan(0);
    expect(page).toBe(0);
  });
});

test('审批记录标签多一个状态筛选，待审批标签没有', async () => {
  renderPage();
  await screen.findByText('SELECT 1');
  expect(screen.queryByLabelText('按状态筛选')).toBeNull();

  await userEvent.click(screen.getByRole('tab', { name: '审批记录' }));
  expect(screen.getByLabelText('按状态筛选')).toBeTruthy();
});

// 待审批要跨数据源看（CLI 在别的库上等审批时不该看不见），审批记录跟着顶栏的数据源走。
test('审批记录按当前数据源过滤，待审批不过滤', async () => {
  // 角标那一路只取一条（pageSize=1），别把它当成列表请求
  const lastListAlias = () => {
    const calls = vi.mocked(getApprovals).mock.calls.filter((call) => call[2] !== 1);
    return calls[calls.length - 1]?.[0]?.alias;
  };

  renderPage();
  await screen.findByText('SELECT 1');
  expect(lastListAlias()).toBe('all');

  await userEvent.click(screen.getByRole('tab', { name: '审批记录' }));
  await waitFor(() => expect(lastListAlias()).toBe('demo'));
});

test('展开详情后显示预检与中文时间线', async () => {
  vi.mocked(getApprovalDetail).mockResolvedValue({
    ...approval(1, 'update'),
    taskRun: { id: 9, status: 'success', createdAt: 1, updatedAt: 2 },
    events: [
      { eventType: 'submitted', createdAt: 1_700_000_000_000 },
      {
        eventType: 'precheck',
        createdAt: 1_700_000_001_000,
        payload: { table: 'users', recoverySupported: false, primaryKey: [], estimatedRows: 7 },
      },
      { eventType: 'executed', createdAt: 1_700_000_002_000, payload: { affectedRows: 7 } },
    ],
  });

  renderPage();
  await userEvent.click((await screen.findAllByRole('button', { name: '详情' }))[0]);

  await waitFor(() => expect(screen.getByText('users')).toBeTruthy());
  // 预估 7 行、实际也是 7 行——两个数并排放才看得出批准时的判断准不准
  expect(screen.getByText('预估影响行数')).toBeTruthy();
  expect(screen.getByText('实际影响行数')).toBeTruthy();
  expect(screen.getAllByText('7')).toHaveLength(2);
  expect(screen.getByText('不可生成')).toBeTruthy();
  expect(screen.getByText('无')).toBeTruthy();
  expect(screen.getByText('提交')).toBeTruthy();
  expect(screen.getByText('执行完成')).toBeTruthy();
  expect(screen.getByText('影响 7 行')).toBeTruthy();
});

test('没有关联任务时说明没采集预检', async () => {
  vi.mocked(getApprovalDetail).mockResolvedValue({
    ...approval(2, 'graph'),
    taskRun: null,
    events: [],
  });

  renderPage();
  await userEvent.click((await screen.findAllByRole('button', { name: '详情' }))[1]);

  await waitFor(() => expect(screen.getByText(/未采集预检/)).toBeTruthy());
});

// 一件等着我决定的事只有一个入口：待审批不按类型拆，图谱卡片多摊开一份 diff 而已。
// 历史侧才拆——图谱的审批记录连着变更流水，混进通用记录里看不出来。
test('待审批不拆类型，审批记录才把图谱排掉', async () => {
  renderPage();
  await screen.findByText('SELECT 1');

  const lastList = () => {
    const calls = vi.mocked(getApprovals).mock.calls.filter((call) => call[2] !== 1);
    return calls[calls.length - 1];
  };
  expect(lastList()?.[0]?.excludeKind).toBeUndefined();
  expect(screen.getByRole('option', { name: '图谱' })).toBeTruthy();

  await userEvent.click(screen.getByRole('tab', { name: '审批记录' }));
  await waitFor(() => expect(lastList()?.[0]?.excludeKind).toBe('graph'));
});

// 图谱审批批的是一次内容变更，不看 before/after 批不下去，所以卡片上直接摊开
test('待审批里的图谱变更摊开 before/after，按钮说明批准才写入', async () => {
  vi.mocked(getApprovals).mockResolvedValue({
    approvals: [{
      ...approval(7, 'graph'),
      payload: {
        targetId: 'column:demo:APP.ORDERS.USER_ID',
        operation: 'upsert_column',
        actor: 'agent',
        baseRevision: 5,
        before: { description: '旧描述' },
        after: { description: '下单用户 ID' },
      },
    }],
    total: 1,
    pending: 1,
  });

  renderPage();
  expect(await screen.findByText('APP.ORDERS.USER_ID')).toBeTruthy();
  expect(screen.getByText('旧描述')).toBeTruthy();
  expect(screen.getByText('下单用户 ID')).toBeTruthy();
  expect(screen.getByRole('button', { name: '批准并写入' })).toBeTruthy();
});

// 图谱标签是历史：还没裁决的在待审批里，这里要把 pending 排掉，否则一件事两个入口。
test('图谱标签只列裁决过的图谱审批', async () => {
  renderPage();
  await userEvent.click(screen.getByRole('tab', { name: '图谱' }));

  await waitFor(() => {
    const calls = vi.mocked(getApprovals).mock.calls.filter((one) => one[2] !== 1);
    const call = calls[calls.length - 1];
    expect(call?.[0]?.kind).toBe('graph');
    expect(call?.[0]?.excludeStatus).toBe('pending');
  });
});

test('?tab=graph 直接落在图谱标签上', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <MemoryRouter initialEntries={['/workspaces/local/reviews?alias=demo&tab=graph']}>
      <QueryClientProvider client={client}>
        <ReviewsPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
  await waitFor(() =>
    expect(screen.getByRole('tab', { name: /图谱/ })).toHaveAttribute('aria-selected', 'true'));
});

// CLI 提交写操作时打印的是「审批 #12 / 任务 #34」。这两个号在页面上必须找得到，
// 否则人拿着 CLI 的提示在这一页对不上号，也没法反过来 `sql-cli task status <id>`。
test('卡片露出审批号与任务号', async () => {
  vi.mocked(getApprovals).mockResolvedValue({
    approvals: [{ ...approval(7, 'update'), taskRunId: 34 }],
    total: 1,
    pending: 1,
  });
  renderPage();

  await screen.findByText('#7');
  expect(await screen.findByText('任务 #34')).toBeInTheDocument();
});
