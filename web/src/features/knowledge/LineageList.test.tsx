import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { LineageList } from './LineageList';

vi.mock('../../api/workspace', () => ({
  getLineageNetworkGraph: vi.fn(),
}));

const api = await import('../../api/workspace');

function renderList(onFocus = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <LineageList onFocus={onFocus} />
    </QueryClientProvider>,
  );
  return onFocus;
}

// 空态是这个视图最常见的状态——多数图谱的 lineage.jsonl 是空的，
// 所以必须说清血缘怎么来、下一步敲什么，而不是「暂无数据」。
test('没有血缘时给出写回命令，而不是一句暂无数据', async () => {
  vi.mocked(api.getLineageNetworkGraph).mockResolvedValue({
    center: '', depth: 0, truncated: false, nodes: [], edges: [], records: [],
  });
  renderList();

  expect(await screen.findByText(/schema add-lineage/)).toBeTruthy();
});

test('有血缘的表一行：几列参与、几条写入，点一行把大图定位过去', async () => {
  vi.mocked(api.getLineageNetworkGraph).mockResolvedValue({
    center: '', depth: 0, truncated: false,
    nodes: [
      { id: 'APP.TASK_POINT.QR_CODE', schema: 'APP', table: 'TASK_POINT', column: 'QR_CODE', level: 0 },
      { id: 'APP.TASK_POINT.SORT', schema: 'APP', table: 'TASK_POINT', column: 'SORT', level: 0 },
      { id: 'APP.PLAN_POINT.SORT', schema: 'APP', table: 'PLAN_POINT', column: 'SORT', level: 1 },
    ],
    edges: [{ from: 'APP.TASK_POINT.SORT', to: 'APP.PLAN_POINT.SORT' }],
    records: [{ id: 'l1', target: 'APP.PLAN_POINT.SORT', sources: ['APP.TASK_POINT.SORT'] }],
  });
  const onFocus = renderList();

  // 写入多的排前面；表名不带 schema
  expect(await screen.findByText('PLAN_POINT')).toBeTruthy();
  expect(screen.getByLabelText('1 条写入')).toBeTruthy();
  expect(screen.getByLabelText('2 列参与')).toBeTruthy();

  await userEvent.click(screen.getByRole('button', { name: /定位 APP.TASK_POINT/ }));
  expect(onFocus).toHaveBeenCalledWith('APP.TASK_POINT');
});
