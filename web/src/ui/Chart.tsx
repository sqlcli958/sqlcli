import { useEffect, useRef } from 'react';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, MarkLineComponent, TooltipComponent } from 'echarts/components';
import { LabelLayout } from 'echarts/features';
import { SVGRenderer } from 'echarts/renderers';
import type { EChartsOption } from 'echarts';

/**
 * ECharts 的唯一包装层。**feature 目录不许直接 import echarts**
 * （`eslint.config.js` 的 `no-restricted-imports` 兜底），要新图表就从这里过。
 *
 * <h2>为什么引了图表库</h2>
 * CLAUDE.md 的判据是「长什么样」还是「怎么动」：按钮扒掉样式只剩一个 div，自己写更划算。
 * 图表越过 sparkline 之后**全是「怎么动」**——刻度取整、resize 重算、tooltip 跟随与贴边、
 * 图例联动、端点标签避让、空数据与单点退化。手写这些跟手写下拉菜单一样，六条里必漏三条。
 *
 * <p>注意 CLAUDE.md 里「第三方 UI 库只有一个」那句话在引它之前就已经不准了：
 * `sigma` + `graphology` 早就在图谱页画关系图，比任何图表库都重。
 *
 * <h2>三条约束</h2>
 * <ul>
 *   <li><b>按需引入</b>。`import * as echarts from 'echarts'` 会把 1MB 全打进包。
 *       这里**只注册当前真的在画的**：折线、直角坐标系、tooltip、markLine、标签避让。
 *       曾经顺手把 `BarChart` 也注册了「留着以后用」，砍掉了——没人用的图种就是白背的体积，
 *       真要画柱状图时在上面加一行 import 即可，别改成整包。</li>
 *   <li><b>SVG 渲染器</b>而不是 Canvas：这几张图数据量都很小，SVG 在高 DPI 下更清楚，
 *       也能被浏览器的查找和无障碍工具读到。数据量大到 SVG 卡了再换。</li>
 *   <li><b>图表库不替你判断该不该画</b>。「这两次评估可不可比」「点够不够画一条线」
 *       这类判断留在各自的 feature 里（见 `features/eval/charts.tsx`），
 *       换渲染器不能把它们一起换掉。</li>
 * </ul>
 */
echarts.use([
  LineChart,
  GridComponent,
  TooltipComponent,
  MarkLineComponent,
  LabelLayout,
  SVGRenderer,
]);

export type { EChartsOption };

export function Chart({
  option,
  height,
  label,
  className,
}: {
  option: EChartsOption;
  /** 高度必须显式给：ECharts 在高度为 0 的容器里静默画不出东西。 */
  height: number;
  /** 无障碍描述，读屏靠它——图本身对读屏是空的。 */
  label: string;
  className?: string;
}) {
  const hostRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts>();

  useEffect(() => {
    if (!hostRef.current) return;
    const chart = echarts.init(hostRef.current, undefined, { renderer: 'svg' });
    chartRef.current = chart;
    // 容器宽度由 CSS 决定，窗口和侧栏折叠都会改它——不监听就会一直用首次渲染的宽度
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(hostRef.current);
    return () => {
      observer.disconnect();
      chart.dispose();
      chartRef.current = undefined;
    };
  }, []);

  useEffect(() => {
    // notMerge：口径变更后 series 会增减，合并会把上一次多出来的那条留在图上
    chartRef.current?.setOption(option, { notMerge: true });
  }, [option]);

  return (
    <div
      ref={hostRef}
      className={className}
      style={{ width: '100%', height }}
      role="img"
      aria-label={label}
    />
  );
}
