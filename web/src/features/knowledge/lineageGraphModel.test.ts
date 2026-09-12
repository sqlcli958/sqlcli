import { expect, test } from 'vitest';
import { groupByTable, reachable, tableOf } from './lineageGraphModel';

// public.qr → task_point.qr → plan_point.start_time；contract 自己一条链，跟前者不连
const edges = [
  { from: 'S.PUBLIC.QR', to: 'S.TASK_POINT.QR' },
  { from: 'S.BUILD.QR', to: 'S.TASK_POINT.QR' },
  { from: 'S.TASK_POINT.QR', to: 'S.PLAN_POINT.START_TIME' },
  { from: 'S.CONTRACT.CLASSIFY', to: 'S.CONTRACT.NO' },
];

test('reachable 两个方向都走到底，不连的链不进来', () => {
  const lit = reachable({ edges }, 'S.TASK_POINT.QR');
  expect([...lit].sort()).toEqual(['S.BUILD.QR', 'S.PLAN_POINT.START_TIME', 'S.PUBLIC.QR', 'S.TASK_POINT.QR']);
  // 源头列：只有下游
  expect([...reachable({ edges }, 'S.PUBLIC.QR')].sort()).toEqual(['S.PLAN_POINT.START_TIME', 'S.PUBLIC.QR', 'S.TASK_POINT.QR']);
  // 环不会死循环
  const cyc = reachable({ edges: [{ from: 'a.b.x', to: 'a.b.y' }, { from: 'a.b.y', to: 'a.b.x' }] }, 'a.b.x');
  expect(cyc.size).toBe(2);
});

test('groupByTable：按表聚、列按名排、其余 N 列 = 总列数减参与数、表内推导记到行上不算边', () => {
  const groups = groupByTable({
    nodes: [
      { id: 'S.CONTRACT.NO', schema: 'S', table: 'CONTRACT', column: 'NO', level: 0 },
      { id: 'S.CONTRACT.CLASSIFY', schema: 'S', table: 'CONTRACT', column: 'CLASSIFY', level: 0 },
      { id: 'S.TASK_POINT.QR', schema: 'S', table: 'TASK_POINT', column: 'QR', level: 0 },
    ],
    edges,
    tableColumns: { 'S.CONTRACT': 50, 'S.TASK_POINT': 1 },
  });
  const contract = groups.find((g) => g.id === 'S.CONTRACT')!;
  expect(contract.columns.map((c) => c.name)).toEqual(['CLASSIFY', 'NO']);
  expect(contract.otherColumns).toBe(48);
  expect(contract.columns.find((c) => c.name === 'NO')!.internalSources).toEqual(['S.CONTRACT.CLASSIFY']);
  expect(contract.columns.find((c) => c.name === 'CLASSIFY')!.internalSources).toEqual([]);
  // 总列数不够或没给：不出负数
  expect(groups.find((g) => g.id === 'S.TASK_POINT')!.otherColumns).toBe(0);
  expect(tableOf('S.T.C')).toBe('S.T');
});
