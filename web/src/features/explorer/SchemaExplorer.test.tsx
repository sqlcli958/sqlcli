import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import { SchemaExplorer } from './SchemaExplorer';
import type { TermDto } from '../../types/api';

vi.mock('../../api/workspace', async () => {
  const actual = await vi.importActual<typeof import('../../api/workspace')>('../../api/workspace');
  return { ...actual, getTables: vi.fn(), getSchemaCatalog: vi.fn(), getTerms: vi.fn() };
});

const { getTables, getSchemaCatalog, getTerms } = await import('../../api/workspace');

const 报事: TermDto = {
  id: 'term:demo:报事',
  name: '报事',
  displayName: '报事',
  aliases: [],
  negativeAliases: [],
  description: null,
  status: 'candidate',
  confidence: 0.8,
  mappedTargets: [],
  primaryTarget: 'table:demo:app.erp_prop_report',
  filters: ['state IN (0,1,6)'],
  scenarioTables: ['app.erp_prop_report', 'app.sys_user'],
  bridgeTables: ['app.sys_user'],
};

function renderExplorer(search: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/graph?alias=demo${search}`]}>
        <SchemaExplorer onSelectTable={() => {}} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  // 调用次数跨用例累积，不清就会拿上一个用例的记录做断言
  vi.clearAllMocks();
  vi.mocked(getSchemaCatalog).mockResolvedValue([
    { name: 'app', imported: true, tableCount: 499, system: false },
    { name: 'other', imported: true, tableCount: 3, system: false },
  ]);
  vi.mocked(getTerms).mockResolvedValue({ terms: [报事], total: 1 });
  // 服务端一页上限 100，这个库有 499 张表——场景要的表根本不在第一页里
  vi.mocked(getTables).mockResolvedValue({
    items: [{
      id: 'table:demo:app.act_evt_log', schema: 'app', name: 'act_evt_log',
      qualifiedName: 'app.act_evt_log', comment: '', description: null,
      tableType: 'base_table', columnCount: 12, tags: [],
    }],
    total: 499,
    offset: 0,
    limit: 100,
  });
});

test('筛场景时表来自场景清单，而不是分页列表', async () => {
  renderExplorer('&term=term%3Ademo%3A%E6%8A%A5%E4%BA%8B');

  // 这是真实踩过的坑：在第一页 100 张上做客户端过滤，场景那十来张一张也匹配不到，
  // 结果是「筛选看起来生效了但一张表都没有」
  expect(await screen.findByText('erp_prop_report')).toBeTruthy();
  expect(screen.getByText('sys_user')).toBeTruthy();
  expect(screen.queryByText('act_evt_log')).toBeNull();
  await waitFor(() => expect(getTables).not.toHaveBeenCalled());
});

test('筛场景时 schema 自动展开，表数显示场景的数量', async () => {
  renderExplorer('&term=term%3Ademo%3A%E6%8A%A5%E4%BA%8B');
  await screen.findByText('erp_prop_report');

  // 不展开的话筛完看到的只是一排收起的 schema，跟没筛一样；
  // 计数写 499 会让人以为筛坏了
  expect(screen.getByText('2')).toBeTruthy();
  expect(screen.queryByText('499')).toBeNull();
  // 不含场景表的 schema 整个不出现
  expect(screen.queryByText('other')).toBeNull();
});

test('没有筛选时照常走分页列表', async () => {
  renderExplorer('');
  await screen.findByText('app');

  expect(getTerms).not.toHaveBeenCalled();
  expect(screen.getByText('499')).toBeTruthy();
});

test('滚到底才取下一页，不是一把梭', async () => {
  // 原来一次请求 200 张，剩下的 299 张在侧栏里根本不存在——
  // 没有翻页也没有滚动，只能靠搜索找
  renderExplorer('');
  // schema 行默认收起，不展开的话表列表压根不会挂载
  await userEvent.click(await screen.findByText('app'));

  await waitFor(() => expect(getTables).toHaveBeenCalled());
  const [, offset, limit] = vi.mocked(getTables).mock.calls[0];
  expect(offset).toBe(0);
  expect(limit).toBe(100);
});

test('哨兵进入视野时取下一页，offset 接着上一页', async () => {
  // 全局桩的 IntersectionObserver 是「永不相交」，这里换成「立刻相交」，
  // 才能验到翻页逻辑本身——浏览器里没法验：后台标签页的 IO 根本不触发
  const original = globalThis.IntersectionObserver;
  globalThis.IntersectionObserver = class {
    constructor(private cb: IntersectionObserverCallback) {}
    observe() {
      this.cb([{ isIntersecting: true } as IntersectionObserverEntry],
        this as unknown as IntersectionObserver);
    }
    unobserve() {}
    disconnect() {}
    takeRecords() { return []; }
    root = null; rootMargin = ''; thresholds = [];
  } as unknown as typeof IntersectionObserver;

  try {
    renderExplorer('');
    await userEvent.click(await screen.findByText('app'));

    await waitFor(() => expect(vi.mocked(getTables).mock.calls.length).toBeGreaterThan(1));
    const offsets = vi.mocked(getTables).mock.calls.map((call) => call[1]);
    // 下一页的 offset 是「已经拿到几条」，不是「第几页 × 页大小」——
    // 服务端返回的条数可能少于请求的（这个 mock 就只返回 1 条），
    // 按页号算会跳过没拿到的那些。这里第二页从 1 开始正是这个意思。
    expect(offsets.slice(0, 2)).toEqual([0, 1]);
  } finally {
    globalThis.IntersectionObserver = original;
  }
});
