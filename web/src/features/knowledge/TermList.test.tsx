import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useSearchParams } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import { TermList } from './TermList';
import type { TermDto } from '../../types/api';

vi.mock('../../api/workspace', async () => {
  const actual = await vi.importActual<typeof import('../../api/workspace')>('../../api/workspace');
  return { ...actual, getTerms: vi.fn(), deleteTerm: vi.fn() };
});

const { getTerms, deleteTerm } = await import('../../api/workspace');

function term(overrides: Partial<TermDto>): TermDto {
  return {
    id: 'term:demo:x',
    name: 'x',
    displayName: 'x',
    aliases: [],
    negativeAliases: [],
    description: null,
    status: 'candidate',
    confidence: 0.8,
    mappedTargets: [],
    primaryTarget: null,
    filters: [],
    scenarioTables: [],
    bridgeTables: [],
    ...overrides,
  };
}

const 报事 = term({
  id: 'term:demo:报事',
  name: '报事',
  displayName: '报事',
  aliases: ['工单'],
  primaryTarget: 'table:demo:app.report',
  filters: ['state IN (0,1,6)', 'subject_id = :subjectId'],
  scenarioTables: ['app.report', 'app.assign'],
});

// 没有入口表 → 展不开子图 → 不该可点
const 顺序巡检 = term({ id: 'term:demo:顺序巡检', name: '顺序巡检', displayName: '顺序巡检' });

function Probe() {
  const [params] = useSearchParams();
  return <div data-testid="term-param">{params.get('term') ?? ''}</div>;
}

function renderList(onPick = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/graph?alias=demo']}>
        <TermList onPick={onPick} />
        <Probe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return onPick;
}

beforeEach(() => {
  vi.mocked(getTerms).mockResolvedValue({ terms: [报事, 顺序巡检], total: 2 });
  vi.mocked(deleteTerm).mockResolvedValue({ newRevision: 8, changeId: 'c8' });
});

test('场景术语可点，点了把 term 写进 URL 并切回表目录', async () => {
  const onPick = renderList();
  const button = await screen.findByRole('button', { name: '报事' });

  await userEvent.click(button);

  // 筛选状态进 URL：可分享、刷新不丢，和 ?alias= 一致
  await waitFor(() => expect(screen.getByTestId('term-param').textContent).toBe('term:demo:报事'));
  // 点完还停在术语 tab 的话，人得自己再点一次表目录才看得到筛选结果
  expect(onPick).toHaveBeenCalled();
});

test('再点一次取消筛选', async () => {
  renderList();
  const button = await screen.findByRole('button', { name: '报事' });

  await userEvent.click(button);
  await waitFor(() => expect(screen.getByTestId('term-param').textContent).toBe('term:demo:报事'));
  await userEvent.click(button);

  await waitFor(() => expect(screen.getByTestId('term-param').textContent).toBe(''));
});

test('没有入口表的术语不可点，也不显示过滤', async () => {
  renderList();
  await screen.findByRole('button', { name: '报事' });

  // 展不开子图的术语点了也没东西可筛，做成可点是骗人
  expect(screen.queryByRole('button', { name: '顺序巡检' })).toBeNull();
  expect(screen.getByText('顺序巡检')).toBeTruthy();
});

test('场景过滤在详情里，一次点击就能看全', async () => {
  renderList();
  // 列表里只留名字和一行说明（四段铺开的话二十条就翻不动了），
  // 但过滤是要被写进 WHERE 的——藏到点不开的地方等于没有
  await userEvent.click((await screen.findAllByRole('button', { name: '术语详情' }))[0]);

  expect(await screen.findByText(/state IN \(0,1,6\)/)).toBeTruthy();
  expect(screen.getByText(/subject_id = :subjectId/)).toBeTruthy();
});

test('确认条说清映射会跟着一起删', async () => {
  vi.mocked(getTerms).mockResolvedValue({
    terms: [{ ...报事, mappedTargets: ['table:demo:app.report', 'table:demo:app.assign'] }],
    total: 1,
  });
  renderList();

  await userEvent.click((await screen.findAllByRole('button', { name: '删除这条术语' }))[0]);

  // 边跟着术语一起删（留一条就是悬空引用），但不能默默多删
  expect(screen.getByText('连同 2 条映射一起删除')).toBeTruthy();
});

test('删除要点两次：先出确认，再真删', async () => {
  renderList();
  await screen.findByRole('button', { name: '报事' });

  // 术语占检索最高权重档，删错了搜索会静默变差，而列表里每条长得都差不多
  await userEvent.click(screen.getAllByRole('button', { name: '删除这条术语' })[0]);
  expect(deleteTerm).not.toHaveBeenCalled();

  await userEvent.click(screen.getByRole('button', { name: '确认删除' }));
  await waitFor(() => expect(deleteTerm).toHaveBeenCalled());
});

test('删掉正被筛的术语时，筛选条件跟着撤掉', async () => {
  renderList();
  const scenario = await screen.findByRole('button', { name: '报事' });
  await userEvent.click(scenario);
  await waitFor(() => expect(screen.getByTestId('term-param').textContent).toBe('term:demo:报事'));

  await userEvent.click(screen.getAllByRole('button', { name: '删除这条术语' })[0]);
  await userEvent.click(screen.getByRole('button', { name: '确认删除' }));

  // 不撤的话表目录会空着，而没人知道为什么空
  await waitFor(() => expect(screen.getByTestId('term-param').textContent).toBe(''));
});

test('后端拒绝时把原因显示出来', async () => {
  vi.mocked(deleteTerm).mockRejectedValue(new Error('这条术语还挂着 2 条映射，先删掉映射关系再删术语'));
  renderList();
  await screen.findByRole('button', { name: '报事' });

  await userEvent.click(screen.getAllByRole('button', { name: '删除这条术语' })[0]);
  await userEvent.click(screen.getByRole('button', { name: '确认删除' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('还挂着 2 条映射');
});

test('删除按钮固定在最右一列，不跟着内容浮动', async () => {
  renderList();
  await screen.findByRole('button', { name: '报事' });

  // 之前这里的选择器写的是 .action-icon，而 ActionIcon 渲染出来是 .btn.is-icon——
  // 选择器没匹配上，margin-left:auto 从没生效，按钮位置每行都不一样
  const icon = screen.getAllByRole('button', { name: '删除这条术语' })[0];
  expect(icon.closest('.term-list-actions')).not.toBeNull();
});

test('映射显示短名不是裸 id', async () => {
  vi.mocked(getTerms).mockResolvedValue({
    terms: [{ ...报事, mappedTargets: ['column:demo:app.erp_prop_report_score.satisfaction'] }],
    total: 1,
  });
  renderList();
  await userEvent.click((await screen.findAllByRole('button', { name: '术语详情' }))[0]);

  // 弹窗也没宽到能放裸 id：column:demo:app.x.y 会把它撑开，而被撑掉的恰恰是表名和列名
  expect(await screen.findByText(/erp_prop_report_score\.satisfaction/)).toBeTruthy();
  expect(screen.queryByText(/column:demo:/)).toBeNull();
});
