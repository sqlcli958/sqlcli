import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { ResultTable } from './ResultTable';

const longText = 'x'.repeat(500);

test('NULL、长文本与截断提示都能读出来', () => {
  render(
    <ResultTable
      result={{
        columns: [
          { name: 'id', type: 'BIGINT' },
          { name: 'note', type: 'VARCHAR' },
        ],
        rows: [
          [1, null],
          [2, longText],
        ],
        rowCount: 2,
        elapsedMs: 12,
        truncated: true,
      }}
    />,
  );

  expect(screen.getByText('NULL')).toBeTruthy();
  // 长文本整段留在 DOM 与 title 里，只由 CSS 截断
  expect(screen.getByTitle(longText).textContent).toBe(longText);
  expect(screen.getByText(/已截断/)).toBeTruthy();
  expect(screen.getByText('note')).toBeTruthy();
});

test('columnMeta 命中的列头显示业务名，值域进 title；没命中的保持裸列名', () => {
  render(
    <ResultTable
      result={{
        columns: [
          { name: 'status', type: 'INT' },
          { name: 'id', type: 'BIGINT' },
        ],
        rows: [[0, 1]],
        rowCount: 1,
        elapsedMs: 5,
        truncated: false,
        columnMeta: {
          status: { businessName: '订单状态', semanticType: 'status', enumValues: ['0=待付款', '1=已付款'] },
        },
      }}
    />,
  );

  expect(screen.getByText('订单状态')).toBeTruthy();
  const header = screen.getByText('status').closest('th')!;
  expect(header.title).toContain('值域：0=待付款、1=已付款');
  // id 没有 meta：列头只有裸列名和类型（历史重放的结果没有 columnMeta，同样走这条路径）
  expect(screen.getByText('id').closest('th')!.textContent).toBe('idBIGINT');
});

test('列头只显示业务名，业务描述不进列头（会挤变形，只能进 title）', () => {
  // columnMeta 目前不带 description，这里用类型断言模拟「万一以后加了」的情况，
  // 保证 ResultTable 不会把它渲染进列头——只认 businessName。
  const metaWithDescription = {
    status: { businessName: '订单状态', description: '订单从下单到完成的流转状态，一段很长的口径说明' },
  } as unknown as NonNullable<Parameters<typeof ResultTable>[0]['result']['columnMeta']>;

  render(
    <ResultTable
      result={{
        columns: [{ name: 'status', type: 'INT' }],
        rows: [[0]],
        rowCount: 1,
        elapsedMs: 5,
        truncated: false,
        columnMeta: metaWithDescription,
      }}
    />,
  );

  expect(screen.getByText('订单状态')).toBeTruthy();
  const header = screen.getByText('status').closest('th')!;
  expect(header.textContent).not.toContain('订单从下单到完成的流转状态');
});

test('没有结果集时不渲染表格', () => {
  render(<ResultTable result={{ columns: [], rows: [], rowCount: 0, elapsedMs: 3, truncated: false }} />);
  expect(screen.queryByRole('table')).toBeNull();
});
