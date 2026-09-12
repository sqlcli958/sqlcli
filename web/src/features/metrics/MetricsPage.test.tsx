import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import { MetricsPage } from './MetricsPage';
import type { AliasDirectoryDto, MetricDto } from '../../types/api';

vi.mock('../../api/metrics', async () => {
  const actual = await vi.importActual<typeof import('../../api/metrics')>('../../api/metrics');
  return {
    ...actual,
    getMetrics: vi.fn(),
    upsertMetric: vi.fn(),
    expandMetricSql: vi.fn(),
  };
});
vi.mock('../../api/aliases', async () => {
  const actual = await vi.importActual<typeof import('../../api/aliases')>('../../api/aliases');
  return { ...actual, getAliases: vi.fn() };
});
vi.mock('../../api/workbench', async () => {
  const actual = await vi.importActual<typeof import('../../api/workbench')>('../../api/workbench');
  return { ...actual, executeWorkbenchSql: vi.fn() };
});

const { getMetrics, upsertMetric, expandMetricSql } = await import('../../api/metrics');
const { getAliases } = await import('../../api/aliases');
const { executeWorkbenchSql } = await import('../../api/workbench');

const gmv: MetricDto = {
  id: 'metric:demo:gmv_paid',
  name: 'gmv_paid',
  businessName: '已支付GMV',
  aliases: ['GMV'],
  expression: 'SUM(app.orders.amount)',
  filters: 'status IN (2,3)',
  grain: { timeColumn: 'column:demo:app.orders.created_at', grains: ['day', 'month'] },
  dimensions: ['column:demo:app.orders.channel'],
  joinPath: [],
  status: 'verified',
  verified: true,
  confidence: 1,
  updatedBy: 'human',
};

const aliasDirectory: AliasDirectoryDto = {
  aliases: [{ name: 'demo', readOnly: false, graphAvailable: true, approveQuery: false, approveUpdate: false, approveGraph: false }],
};

/** MetricsPage 读 useOutletContext，测试里得真有一层 Outlet 提供它。 */
function Wrapper({ graphAvailable }: { graphAvailable: boolean }) {
  return (
    <Routes>
      <Route element={<Outlet context={{ graphAvailable }} />}>
        <Route path="/" element={<MetricsPage />} />
      </Route>
    </Routes>
  );
}

function renderPage({ graphAvailable = true, alias = 'demo' }: { graphAvailable?: boolean; alias?: string } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[alias ? `/?alias=${alias}` : '/']}>
        <Wrapper graphAvailable={graphAvailable} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
  vi.mocked(getMetrics).mockResolvedValue({ metrics: [gmv], revision: 7 });
  vi.mocked(getAliases).mockResolvedValue(aliasDirectory);
});

test('列出口径：表达式、过滤、时间列与维度都显示成可读列名', async () => {
  renderPage();

  expect(await screen.findByText('已支付GMV')).toBeTruthy();
  expect(screen.getByText('口径：SUM(app.orders.amount)')).toBeTruthy();
  // 口径争议基本都在过滤条件这一行，不能只显示聚合函数
  expect(screen.getByText('过滤：status IN (2,3)')).toBeTruthy();
  // 列 id 在前端切成可读引用，服务端不翻译
  expect(screen.getByText(/app\.orders\.created_at/)).toBeTruthy();
  expect(screen.getByText('维度：app.orders.channel')).toBeTruthy();
});

test('展开 SQL 后可以直接送去工作台执行', async () => {
  vi.mocked(expandMetricSql).mockResolvedValue({ metric: 'gmv_paid', sql: 'SELECT 1' });
  renderPage();
  await screen.findByText('已支付GMV');

  await userEvent.selectOptions(screen.getByLabelText('展开粒度'), 'day');
  await userEvent.click(screen.getByRole('button', { name: '展开 SQL' }));

  await waitFor(() => expect(screen.getByText('SELECT 1')).toBeTruthy());
  expect(vi.mocked(expandMetricSql).mock.calls[0][1]).toEqual({ grain: 'day' });

  await userEvent.click(screen.getByRole('link', { name: '去工作台执行' }));
  expect(sessionStorage.getItem('sql-cli-workbench-pending-sql')).toBe('SELECT 1');
});

test('新建指标带上当前 revision，列引用按 schema.table.column 原样提交', async () => {
  vi.mocked(upsertMetric).mockResolvedValue({ newRevision: 8, metricId: 'metric:demo:aov' });
  renderPage();
  await screen.findByText('已支付GMV');

  await userEvent.click(screen.getByRole('button', { name: '新建指标' }));
  await userEvent.type(screen.getByLabelText('名称'), 'aov');
  await userEvent.type(screen.getByLabelText('口径表达式'), 'AVG(app.orders.amount)');
  await userEvent.type(screen.getByLabelText('维度'), 'app.orders.channel');
  await userEvent.click(screen.getByRole('button', { name: '保存' }));

  await waitFor(() => expect(upsertMetric).toHaveBeenCalled());
  expect(vi.mocked(upsertMetric).mock.calls[0][0]).toMatchObject({
    name: 'aov',
    expression: 'AVG(app.orders.amount)',
    dimensions: ['app.orders.channel'],
    // revision 取列表接口返回的那一份，不是会话里可能已经过期的那个
    expectedRevision: 7,
  });
});

test('比率指标勾选后按分子/分母提交，不带顶层 expression', async () => {
  vi.mocked(upsertMetric).mockResolvedValue({ newRevision: 8, metricId: 'metric:demo:repeat_rate' });
  renderPage();
  await screen.findByText('已支付GMV');

  await userEvent.click(screen.getByRole('button', { name: '新建指标' }));
  await userEvent.type(screen.getByLabelText('名称'), 'repeat_rate');
  await userEvent.click(screen.getByLabelText('比率指标'));
  await userEvent.type(screen.getByLabelText('分子表达式'), 'COUNT(DISTINCT app.orders.user_id)');
  await userEvent.type(screen.getByLabelText('分子过滤'), 'order_count >= 2');
  await userEvent.type(screen.getByLabelText('分母表达式'), 'COUNT(DISTINCT app.orders.user_id)');
  await userEvent.click(screen.getByRole('button', { name: '保存' }));

  await waitFor(() => expect(upsertMetric).toHaveBeenCalled());
  const body = vi.mocked(upsertMetric).mock.calls[0][0];
  expect(body).toMatchObject({
    name: 'repeat_rate',
    numerator: { expression: 'COUNT(DISTINCT app.orders.user_id)', filters: 'order_count >= 2' },
    denominator: { expression: 'COUNT(DISTINCT app.orders.user_id)' },
  });
  expect(body.expression).toBeUndefined();
});

test('没填表达式就不能保存——没有聚合表达式的指标展不开 SQL', async () => {
  renderPage();
  await screen.findByText('已支付GMV');

  await userEvent.click(screen.getByRole('button', { name: '新建指标' }));
  await userEvent.type(screen.getByLabelText('名称'), 'aov');

  expect(screen.getByRole('button', { name: '保存' }).hasAttribute('disabled')).toBe(true);
});

test('没有声明时间列的指标不给出图入口，只给补时间列的 CLI 命令', async () => {
  vi.mocked(getMetrics).mockResolvedValue({
    metrics: [{ ...gmv, grain: null }],
    revision: 7,
  });
  renderPage();
  await screen.findByText('已支付GMV');

  expect(screen.getByText(/没有声明时间列/)).toBeTruthy();
  expect(screen.queryByRole('button', { name: '出图' })).toBeNull();
});

test('出图：展开 SQL 走 executeWorkbenchSql，不另写执行路径，画出折线', async () => {
  vi.mocked(expandMetricSql).mockResolvedValue({ metric: 'gmv_paid', sql: 'SELECT grain_day, gmv_paid FROM x' });
  vi.mocked(executeWorkbenchSql).mockResolvedValue({
    status: 'SUCCEEDED',
    sqlType: 'SELECT',
    affectedRows: null,
    recoveryId: null,
    taskId: null,
    precheck: null,
    errorSummary: null,
    columns: [{ name: 'grain_day', type: 'date' }, { name: 'gmv_paid', type: 'number' }],
    rows: [
      ['2024-01-01', 100],
      ['2024-01-02', 120],
    ],
    rowCount: 2,
    elapsedMs: 5,
    truncated: false,
  });
  renderPage();
  await screen.findByText('已支付GMV');

  await userEvent.click(screen.getByRole('button', { name: '出图' }));

  await waitFor(() => expect(executeWorkbenchSql).toHaveBeenCalledWith('SELECT grain_day, gmv_paid FROM x'));
  // 展开时带上了默认粒度和一段时间窗——不是裸调用
  expect(vi.mocked(expandMetricSql).mock.calls[0][1]).toMatchObject({ grain: 'day' });
  expect(await screen.findByRole('img', { name: /已支付GMV 趋势/ })).toBeTruthy();
});

test('出图查询为空结果时不画空网格，说明没有数据', async () => {
  vi.mocked(expandMetricSql).mockResolvedValue({ metric: 'gmv_paid', sql: 'SELECT 1' });
  vi.mocked(executeWorkbenchSql).mockResolvedValue({
    status: 'SUCCEEDED',
    sqlType: 'SELECT',
    affectedRows: null,
    recoveryId: null,
    taskId: null,
    precheck: null,
    errorSummary: null,
    columns: [{ name: 'grain_day', type: 'date' }, { name: 'gmv_paid', type: 'number' }],
    rows: [],
    rowCount: 0,
    elapsedMs: 5,
    truncated: false,
  });
  renderPage();
  await screen.findByText('已支付GMV');

  await userEvent.click(screen.getByRole('button', { name: '出图' }));

  expect(await screen.findByText('这个时间范围内没有数据。')).toBeTruthy();
  expect(screen.queryByRole('img', { name: /趋势/ })).toBeNull();
});
