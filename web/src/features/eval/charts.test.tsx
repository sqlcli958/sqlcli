import { expect, test } from 'vitest';
import { buildTrendOption, comparablePairs, type TrendPoint } from './charts';

/**
 * 趋势图的**判断**，不是它的像素。
 *
 * 这几条原来是靠 `querySelectorAll('.eval-trend polyline')` 在页面测里断言的——
 * 那把测试焊死在「自绘 SVG」这个实现上，迁到 ECharts 就全红，而被测的性质
 * 一条都没变。它们本来就是数据层面的判断，测 option 比刮 DOM 稳。
 *
 * 每一条都对应一次真实的误读，见 `charts.tsx` 里各自的注释。
 */

type Series = { name?: string; data?: unknown[]; endLabel?: { formatter?: unknown } };
const seriesOf = (option: ReturnType<typeof buildTrendOption>) =>
  (option.series as Series[]).filter((item) => item.name != null);

const labelOf = (key: string) => ({ a: '表描述覆盖', b: '字段描述覆盖', c: '库自带注释' }[key] ?? key);

test('只画传进来的那几个指标——注释镜像被上游剔掉后就不该出现在图里', () => {
  const points: TrendPoint[] = [
    { at: 1, values: { a: 0.1, b: 0.2, c: 0.86 } },
    { at: 2, values: { a: 0.3, b: 0.4, c: 0.86 } },
  ];
  // 页面传的 keys 里没有 c：库自带注释导入即有，画进趋势会让图看着一开始就很高
  const option = buildTrendOption({ points, keys: ['a', 'b'], labelOf });

  expect(seriesOf(option).map((item) => item.name)).toEqual(['表描述覆盖', '字段描述覆盖']);
  expect(JSON.stringify(option.series)).not.toContain('库自带');
});

test('端点标签取最后一个点的值', () => {
  const points: TrendPoint[] = [
    { at: 1, values: { a: 0.1 } },
    { at: 2, values: { a: 0.3 } },
  ];
  const option = buildTrendOption({ points, keys: ['a'], labelOf });

  // 端点标签是亮色下的对比度缓解手段，不是装饰；取错点等于图上写了个错数
  expect(seriesOf(option)[0].endLabel?.formatter).toBe('表描述覆盖 30%');
});

test('口径变更处插 null 断开，并画一条说明竖线', () => {
  const points: TrendPoint[] = [
    { at: 1, values: { a: 0.86, b: 0.5 } },
    // 第三个点少了 b：指标键集合变了 = 中间改过口径，两侧不可比
    { at: 2, values: { a: 0.86, b: 0.5 } },
    { at: 3, values: { a: 0.02 } },
  ];
  const option = buildTrendOption({ points, keys: ['a'], labelOf });

  // 连过去就等于宣称 86%→2% 是图谱变化，而真实原因是口径改了
  expect(seriesOf(option)[0].data).toEqual([0.86, 0.86, null, 0.02]);
  expect(JSON.stringify(option.series)).toContain('指标口径变更，两侧不可比');
});

test('y 轴固定 0~100% 四分位，不按数据缩放', () => {
  const points: TrendPoint[] = [
    { at: 1, values: { a: 0.30 } },
    { at: 2, values: { a: 0.32 } },
  ];
  const yAxis = buildTrendOption({ points, keys: ['a'], labelOf }).yAxis as {
    min: number; max: number; interval: number;
  };

  // 自动缩放会把 2 个百分点的波动画成翻天覆地
  expect([yAxis.min, yAxis.max, yAxis.interval]).toEqual([0, 1, 0.25]);
});

test('相邻两次的指标键集合不同就不算可比的一对', () => {
  const same: TrendPoint[] = [
    { at: 1, values: { a: 0.1, b: 0.2 } },
    { at: 2, values: { a: 0.3, b: 0.4 } },
  ];
  const changed: TrendPoint[] = [
    { at: 1, values: { a: 0.1, b: 0.2 } },
    { at: 2, values: { a: 0.3 } },
  ];

  expect(comparablePairs(same)).toBe(1);
  // 一对都没有时页面不画空网格——带坐标轴的孤点图看着像功能坏了
  expect(comparablePairs(changed)).toBe(0);
});
