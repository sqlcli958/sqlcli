import { expect, test } from 'vitest';
import { buildMetricTrendOption, extractMetricSeries, grainColumnName } from './charts';
import type { WorkbenchExecuteResultDto } from '../../types/api';

/**
 * 指标出图的**判断**，不是它的像素——同样的取舍见 `features/eval/charts.test.tsx`。
 */

function result(columns: string[], rows: (string | number | null)[][]): WorkbenchExecuteResultDto {
  return {
    columns: columns.map((name) => ({ name, type: 'text' })),
    rows,
    rowCount: rows.length,
    elapsedMs: 1,
    truncated: false,
    status: 'SUCCEEDED',
    sqlType: 'SELECT',
    affectedRows: null,
    recoveryId: null,
    taskId: null,
    precheck: null,
    errorSummary: null,
  };
}

test('没有时间列时取不到点——调用方据此不画图，只说明原因', () => {
  const r = result(['other_col', 'gmv_paid'], [['2024-01-01', 100]]);
  expect(extractMetricSeries(r, grainColumnName('day'), 'gmv_paid')).toEqual([]);
});

test('空结果取不到点——不画带坐标轴的空网格', () => {
  const r = result(['grain_day', 'gmv_paid'], []);
  expect(extractMetricSeries(r, 'grain_day', 'gmv_paid')).toEqual([]);
});

test('按时间升序排列，值为 null 的行被丢弃', () => {
  const r = result(
    ['grain_day', 'gmv_paid'],
    [
      ['2024-01-03', 300],
      ['2024-01-01', 100],
      ['2024-01-02', null],
    ],
  );
  expect(extractMetricSeries(r, 'grain_day', 'gmv_paid')).toEqual([
    { x: '2024-01-01', y: 100 },
    { x: '2024-01-03', y: 300 },
  ]);
});

test('y 轴自动缩放，不写死 0~1、不强制从 0 起', () => {
  const option = buildMetricTrendOption([{ x: 'a', y: 5 }], 'gmv_paid');
  const yAxis = option.yAxis as { min?: unknown; max?: unknown; interval?: unknown };
  expect(yAxis.min).toBeUndefined();
  expect(yAxis.max).toBeUndefined();
  expect(yAxis.interval).toBeUndefined();
});

test('grain 列名按后端约定拼：grain_<grain>，小写', () => {
  expect(grainColumnName('day')).toBe('grain_day');
  expect(grainColumnName('MONTH')).toBe('grain_month');
});

test('列名对不上时返回空——调用方据此区分「没数据」和「认不出列」', () => {
  // SQL 起的别名跟约定不一致（比如 MetricSqlExpander 改了命名），
  // 这时 rows 是有的。页面必须说「认不出该画哪两列」而不是「没有数据」——
  // 后者是假话，而且是那种照跑、有结果、没人发现的静默错误。
  const result = {
    status: 'SUCCEEDED',
    columns: [{ name: 'the_day' }, { name: 'the_value' }],
    rows: [['2026-09-01', 12]],
  } as unknown as Parameters<typeof extractMetricSeries>[0];

  expect(extractMetricSeries(result, 'grain_day', '完成率')).toEqual([]);
  expect(result.rows.length).toBeGreaterThan(0);
});
