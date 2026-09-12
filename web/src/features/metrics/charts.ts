import type { EChartsOption } from '../../ui/Chart';
import type { WorkbenchExecuteResultDto } from '../../types/api';

/**
 * 指标出图的判断层：从 `executeWorkbenchSql` 的结果里挑点、搭 option。
 * 抽成纯函数导出是为了能直接测「该不该画」这几条判断，不用去刮 ECharts 渲染出来的
 * DOM（那会把测试焊死在渲染器上）——同样的取舍见 `features/eval/charts.tsx`。
 */

export interface MetricPoint {
  x: string;
  y: number;
}

/**
 * `expand-metric` 按 grain 生成的时间桶列名固定是 `grain_<grain>`，
 * 值列固定是指标自己的 name（见后端 `MetricSqlExpander#expand` 的
 * `NUMERATOR_ALIAS`/列别名规则）——这里跟随同一个约定，不解析 SQL 文本去猜列名。
 */
export function grainColumnName(grain: string): string {
  return `grain_${grain.toLowerCase()}`;
}

/**
 * 从执行结果里挑出时间列和口径值列，拼成按时间升序的点。
 *
 * 挑不到列（没声明时间列、列名跟约定对不上）或者没有可用行时返回空数组——
 * 调用方据此显示「没有数据」，不画一张带坐标轴的空网格
 * （空网格看着像功能坏了，事实是数据不够，跟 eval 页同一个道理）。
 */
export function extractMetricSeries(
  result: WorkbenchExecuteResultDto,
  timeColumnName: string,
  valueColumnName: string,
): MetricPoint[] {
  const timeIdx = result.columns.findIndex((c) => c.name === timeColumnName);
  const valueIdx = result.columns.findIndex((c) => c.name === valueColumnName);
  if (timeIdx < 0 || valueIdx < 0) return [];
  const points: MetricPoint[] = [];
  for (const row of result.rows) {
    const x = row[timeIdx];
    const y = row[valueIdx];
    if (x == null || y == null) continue;
    points.push({ x: String(x), y: Number(y) });
  }
  points.sort((a, b) => (a.x < b.x ? -1 : a.x > b.x ? 1 : 0));
  return points;
}

/**
 * 折线图 option。**y 轴按数据自动缩放，不写死 0~1、也不强制从 0 起**——
 * 指标的量纲未知（可能是金额，可能是 0~100 的百分比），评估页覆盖率那张图的
 * `min:0 max:1 interval:0.25` 是比例专用的，照搬会把有意义的波动压平或压扁。
 */
export function buildMetricTrendOption(points: MetricPoint[], seriesLabel: string): EChartsOption {
  return {
    grid: { top: 16, right: 16, bottom: 40, left: 56 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: points.map((p) => p.x) },
    yAxis: { type: 'value' },
    series: [
      {
        type: 'line' as const,
        name: seriesLabel,
        data: points.map((p) => p.y),
        showSymbol: points.length <= 60,
      },
    ],
  } as EChartsOption;
}
