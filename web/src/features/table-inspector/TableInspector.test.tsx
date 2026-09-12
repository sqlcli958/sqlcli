import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { ColumnLineagePanel } from './TableInspector';
import type { LineageGraphDto } from '../../types/api';

test('有血缘的字段能看到图节点和边，expression/through 不丢', () => {
  const graph: LineageGraphDto = {
    center: 'app.order_summary.total_amount',
    depth: 2,
    truncated: false,
    nodes: [
      { id: 'app.order_summary.total_amount', schema: 'app', table: 'order_summary', column: 'total_amount', level: 0 },
      { id: 'app.orders.price', schema: 'app', table: 'orders', column: 'price', level: -1 },
    ],
    edges: [
      {
        from: 'app.orders.price',
        to: 'app.order_summary.total_amount',
        expression: 'SUM(price)',
        through: 'order_summary_view',
      },
    ],
  };

  render(<ColumnLineagePanel graph={graph} targetRef="app.order_summary.total_amount" />);

  expect(screen.getByText('order_summary')).toBeTruthy();
  expect(screen.getByText('total_amount')).toBeTruthy();
  expect(screen.getByText('orders')).toBeTruthy();
  expect(screen.getByText('price')).toBeTruthy();
  expect(screen.getByText('SUM(price)')).toBeTruthy();
  // through 放在 <title> 悬浮提示里，和 expression 一起，不能丢
  expect(screen.getByText('SUM(price) · order_summary_view', { selector: 'title' })).toBeTruthy();
});

test('无血缘的字段渲染空态，并给出怎么补血缘的指引', () => {
  const graph: LineageGraphDto = {
    center: 'app.orders.price',
    depth: 2,
    truncated: false,
    nodes: [{ id: 'app.orders.price', schema: 'app', table: 'orders', column: 'price', level: 0 }],
    edges: [],
  };

  render(<ColumnLineagePanel graph={graph} targetRef="app.orders.price" />);

  expect(screen.getByText(/该字段暂无血缘记录/)).toBeTruthy();
  expect(screen.getByText(/需要 Agent 读代码/)).toBeTruthy();
  expect(screen.getByText(/schema add-lineage --target app\.orders\.price/)).toBeTruthy();
});

test('节点数超过展示上限时提示收窄深度', () => {
  const graph: LineageGraphDto = {
    center: 'app.orders.price',
    depth: 3,
    truncated: true,
    nodes: [
      { id: 'app.orders.price', schema: 'app', table: 'orders', column: 'price', level: 0 },
      { id: 'app.order_summary.total_amount', schema: 'app', table: 'order_summary', column: 'total_amount', level: 1 },
    ],
    edges: [
      { from: 'app.orders.price', to: 'app.order_summary.total_amount' },
    ],
  };

  render(<ColumnLineagePanel graph={graph} targetRef="app.orders.price" />);

  expect(screen.getByText('节点较多，已收起一部分——缩小深度更容易看清。')).toBeTruthy();
});
