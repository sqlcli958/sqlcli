import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { AppShell } from './AppShell';

vi.mock('../api/approvals', () => ({ getApprovals: vi.fn() }));
vi.mock('../api/session', () => ({ getSession: vi.fn() }));
vi.mock('../api/aliases', () => ({ getAliases: vi.fn(), testAlias: vi.fn() }));
vi.mock('../api/indexApi', () => ({ getIndexStatus: vi.fn() }));

const { getApprovals } = await import('../api/approvals');
const { getSession } = await import('../api/session');
const { getAliases, testAlias } = await import('../api/aliases');
const { getIndexStatus } = await import('../api/indexApi');

beforeEach(() => {
  vi.mocked(getSession).mockResolvedValue({
    alias: 'demo', revision: 1, readOnly: false, capabilities: [], graphAvailable: true,
  } as never);
  vi.mocked(getAliases).mockResolvedValue({ aliases: [] } as never);
  vi.mocked(testAlias).mockResolvedValue({ success: true } as never);
  vi.mocked(getIndexStatus).mockResolvedValue({ status: 'ready' } as never);
});

function renderShell() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter initialEntries={['/workspaces/local/sql?alias=demo']}>
      <QueryClientProvider client={client}>
        <Routes>
          <Route path="/workspaces/local/*" element={<AppShell />} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

/**
 * 待审批角标：没有它，队列里的东西只有主动点进评审页才知道。
 * 实测 6 条待审批从提交那天挂了一整天，而同期图谱一个字节没长。
 */
test('评审导航项带出待审批条数', async () => {
  vi.mocked(getApprovals).mockResolvedValue({ approvals: [], total: 6, pending: 6 } as never);

  renderShell();

  await waitFor(() => expect(screen.getByTitle('6 件等着裁决')).toHaveTextContent('6'));
});

/** 队列空时不留一个 0：常驻的「0」和没有角标传递的信息一样，只多占一块地方。 */
test('没有待审批时不显示角标', async () => {
  vi.mocked(getApprovals).mockResolvedValue({ approvals: [], total: 0, pending: 0 } as never);

  renderShell();

  await waitFor(() => expect(screen.getByText('评审')).toBeInTheDocument());
  expect(screen.queryByTitle(/件等着裁决/)).toBeNull();
});

/**
 * 角标跨数据源：后端返回的 `pending` 不跟筛选条件走，前端也不许按当前别名过滤——
 * CLI 在别的库上排的队，不该因为顶栏选中的是另一个别名就看不见。
 */
test('角标不按当前别名过滤', async () => {
  vi.mocked(getApprovals).mockResolvedValue({ approvals: [], total: 0, pending: 3 } as never);

  renderShell();

  await waitFor(() => expect(screen.getByTitle('3 件等着裁决')).toBeInTheDocument());
  expect(vi.mocked(getApprovals).mock.calls[0][0]).not.toHaveProperty('alias');
});
