/**
 * 评估页的三个图。**只有趋势折线走 ECharts（`ui/Chart.tsx`），另外两个仍然手绘。**
 *
 * 判据还是 CLAUDE.md 那条「长什么样 vs 怎么动」，只是三个图落在两边：
 * <ul>
 *   <li>{@link TrendChart} —— 坐标轴、多序列、断点分段、端点标签避让、resize 重算，
 *       全是「怎么动」，手写必漏。**迁到 ECharts**。</li>
 *   <li>{@link Sparkline} —— 68×22、无坐标轴、4 个点的一条 polyline。为它在每个 KPI tile
 *       起一个 ECharts 实例是更差的方案（5 个实例、5 个 ResizeObserver，画 5 条折线）。**留手写**。</li>
 *   <li>{@link ProbeBars} —— 它不是条形图，是一个 `<ul>`：severity 由行首 StatusDot 表达
 *       （CLAUDE.md「状态色」要求三态展示只用 StatusDot），长度只是 div 宽度。
 *       换成 ECharts 反而丢掉这条约束。**留手写**。</li>
 * </ul>
 *
 * <p>本文件里**判断该不该画**的那部分（{@link comparablePairs}、口径变更断点、
 * 点数下限）跟渲染器无关，换库不能一起换掉——那些是拿真实误读换来的，见各自注释。
 */
import { useMemo } from 'react';
import { Chart, type EChartsOption } from '../../ui/Chart';
import { StatusDot } from '../../ui/StatusDot';

/**
 * categorical 前三槽，已过 CVD 校验（亮色 ΔE 9.2、常视 27.6）。
 *
 * 亮色下 aqua 对比度 2.74:1 触发 WARN，**缓解方式是线端直接标注数值**——
 * 那是硬要求不是装饰，所以 `TrendChart` 的端点标签不能删。
 * 超过三条时循环取色并改虚线：端点标签自带指标名，靠标签而不是靠颜色区分。
 */
export const PALETTE = ['#2a78d6', '#eb6834', '#1baf7a'];

export function seriesColor(index: number): string {
  return PALETTE[index % PALETTE.length];
}

function seriesDash(index: number): string | undefined {
  return index < PALETTE.length ? undefined : '5 3';
}

/** 0~1 的比例渲染成整数百分比。 */
export function percent(value: number): string {
  return `${Math.round(value * 100)}%`;
}

/**
 * KPI tile 里的迷你趋势线。没有坐标轴、没有刻度——它回答的只有「在往上还是往下」。
 * 值域按自身 min/max 拉满，因为 tile 里的绝对值旁边已经写着了。
 */
/**
 * 一条趋势线要有几个点才值得画。
 *
 * <p>两三个点的 sparkline 是**噪音**：它按自身 min/max 拉满，于是 3%→3% 的平线和
 * 86%→0% 的崩盘画出来形状差不多，读者只会读错。绝对值就在旁边那行大字上，
 * 点不够时留空比画一条骗人的线好。
 */
const MIN_SPARK_POINTS = 4;

export function Sparkline({ values, color }: { values: number[]; color: string }) {
  if (values.length < MIN_SPARK_POINTS) return null;
  const w = 68;
  const h = 22;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const points = values.map((value, i) => {
    const x = (i / (values.length - 1)) * (w - 2) + 1;
    const y = h - 3 - ((value - min) / span) * (h - 6);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
  const [lastX, lastY] = points[points.length - 1].split(',');
  return (
    <svg className="eval-spark" viewBox={`0 0 ${w} ${h}`} width={w} height={h} aria-hidden="true">
      <polyline points={points.join(' ')} fill="none" stroke={color} strokeWidth="1.6" />
      <circle cx={lastX} cy={lastY} r="2" fill={color} />
    </svg>
  );
}

export interface TrendPoint {
  at: number;
  values: Record<string, number>;
}

/**
 * 两次评估的**指标键集合**不同 = 中间改过口径，这一段不能连线。
 *
 * <p>真实事故：`columnDescription` 从 86% 掉到 0%，看起来像图谱被人清空了，
 * 实际是那次把库自带注释从 description 的口径里剔了出去。**一个会误导的图比没有图糟**，
 * 而这件事没有任何图表库会替你发现——它只能从数据本身推。
 *
 * <p>判据用键集合而不是版本号：口径变更总是伴随指标增减，而版本号得有人记得写。
 */
function sameShape(a: TrendPoint, b: TrendPoint): boolean {
  const ka = Object.keys(a.values).sort();
  const kb = Object.keys(b.values).sort();
  return ka.length === kb.length && ka.every((key, i) => key === kb[i]);
}

/**
 * 有几对相邻评估是可比的（口径相同）。**0 就画不出任何一条线**。
 *
 * <p>这时候不要画那张空网格：一个带坐标轴、只有几个孤点的图，看起来像功能坏了，
 * 而事实是「数据还不够」。说出来比画出来诚实，也省得人对着它找哪里出了问题。
 */
export function comparablePairs(points: TrendPoint[]): number {
  let n = 0;
  for (let i = 1; i < points.length; i++) if (sameShape(points[i - 1], points[i])) n++;
  return n;
}

/** 图高与内边距：端点标签避让要按像素算，所以这两个数必须和 <Chart height> 对上。 */
const CHART_H = 230;
const GRID = { top: 16, right: 168, bottom: 34, left: 48 };
/** 两个端点标签至少隔这么多像素，否则数值接近时会叠掉。 */
const LABEL_GAP_PX = 14;

function tickDate(at: number): string {
  const d = new Date(at);
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/**
 * 覆盖率趋势，**单轴**。
 *
 * <p>硬错误数不进这张图：它是计数不是百分比，塞进来就得开第二个 y 轴，
 * 而双轴是可视化的头号错误（两个尺度必然被误读）。硬错误只进 KPI tile 的 sparkline。
 * y 轴**固定 0~100%，不按数据缩放**——缩放会让 2 个百分点的波动看起来像翻天覆地，
 * 所以这里显式写死 min/max，不能交给 ECharts 自动算。
 *
 * <p>迁到 ECharts 之后有两件事从手写代码变成了配置，正是引库的理由：
 * 端点标签的重叠避让（原来手算「最少隔 13px」）现在是 `labelLayout.moveOverlap`，
 * 容器宽度变化重算现在在 {@link Chart} 里统一做。而**断点分段仍然要自己算**——
 * 没有任何图表库会替你发现「这两次评估的口径不一样」。
 */
export interface TrendChartProps {
  points: TrendPoint[];
  keys: string[];
  labelOf: (key: string) => string;
}

/**
 * 把趋势数据搭成 ECharts option。**导出是为了能直接测判断，不去刮 ECharts 的 DOM。**
 *
 * <p>原来这几条性质（几条线、注释镜像不在其中、口径变更处断开）是靠
 * `querySelectorAll('.eval-trend polyline')` 断言的——那把测试焊死在了渲染器上，
 * 换库就全红。它们本来就是**数据层面的判断**，在这一层测更准也更稳。
 */
export function buildTrendOption({ points, keys, labelOf }: TrendChartProps): EChartsOption {
  {
    // 口径变更的下标：这些点跟前一个点之间不连线，并画一条竖线说明为什么
    const breaks: number[] = [];
    for (let i = 1; i < points.length; i++) {
      if (!sameShape(points[i - 1], points[i])) breaks.push(i);
    }

    // 端点标签的重叠避让**得自己算**：ECharts 的 labelLayout.moveOverlap 不作用于
    // endLabel（实测三条 0%/0%/3% 照样叠成一坨）。这是原来手写 SVG 时就有的逻辑，
    // 迁库没能把它省掉——标签是亮色下 aqua 对比度 2.74:1 的缓解手段，叠掉就等于没有。
    // 按值从小到大排，自下而上至少隔 LABEL_GAP_PX，换算成 endLabel 的 y 偏移。
    const lastOf = (key: string) => {
      const raw = points[points.length - 1]?.values[key];
      return typeof raw === 'number' ? raw : null;
    };
    const yOf = (value: number) => GRID.top + (1 - value) * (CHART_H - GRID.top - GRID.bottom);
    const labelDy: Record<string, number> = {};
    let floor = Infinity;
    for (const item of keys
      .map((key) => ({ key, value: lastOf(key) }))
      .filter((item): item is { key: string; value: number } => item.value != null)
      .sort((a, b) => a.value - b.value)) {
      const wanted = Math.min(yOf(item.value), floor - LABEL_GAP_PX);
      labelDy[item.key] = wanted - yOf(item.value);
      floor = wanted;
    }

    const series = keys.map((key, index) => {
      const color = seriesColor(index);
      const lastValue = lastOf(key);
      // 断点处插一个 null 断线：跨过断点连一条线，等于宣称那个跌幅是图谱变化，
      // 而真实事故里那正好是口径改了（columnDescription 86%→0%）
      const withGaps: (number | null)[] = [];
      points.forEach((point, i) => {
        if (breaks.includes(i)) withGaps.push(null);
        const value = point.values[key];
        withGaps.push(typeof value === 'number' ? value : null);
      });
      return {
        type: 'line' as const,
        name: labelOf(key),
        data: withGaps,
        showSymbol: true,
        symbolSize: 5,
        connectNulls: false,
        lineStyle: { width: 2, color, type: seriesDash(index) ? ('dashed' as const) : ('solid' as const) },
        itemStyle: { color },

        // 端点标签是**硬要求不是装饰**：亮色下 aqua 对比度 2.74:1 触发 WARN，
        // 缓解方式就是线端直接标注数值。超过三条时颜色循环，更得靠标签区分。
        endLabel: {
          show: lastValue != null,
          color,
          // **显式取最后一个点的值**，不用 params.value：endLabel 的「末端」由 ECharts
          // 自己判定，插了 null 断线之后它取到的不一定是我们要的那一个（实测过取成了首点）。
          formatter: lastValue == null ? '' : `${labelOf(key)} ${percent(lastValue)}`,
          offset: [0, labelDy[key] ?? 0],
        },
      };
    });

    // 断点在插了 null 之后整体右移，x 轴刻度也要跟着插一个空位
    const categories: string[] = [];
    points.forEach((point, i) => {
      if (breaks.includes(i)) categories.push('');
      categories.push(tickDate(point.at));
    });

    const markLineData = breaks.map((i, order) => ({
      xAxis: i + order,
      label: {
        formatter: '指标口径变更，两侧不可比',
        position: 'insideEndTop' as const,
        // 不写 rotate 的话标签跟着竖线转 90°，一行字竖着排下来没人读
        rotate: 0,
        align: 'left' as const,
      },
    }));

    return {
      grid: GRID,
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'category',
        data: categories,
        axisLabel: { showMaxLabel: true },
      },
      yAxis: {
        type: 'value',
        min: 0,
        max: 1,
        // 刻度写死四分位，不让 ECharts 自动取（它会取 0/20/40/…）：
        // 覆盖率读四分位更自然，而且这是迁库之前手绘的那套刻度，视觉不变
        interval: 0.25,
        axisLabel: { formatter: (value: number) => percent(value) },
      },
      series: markLineData.length
        ? [
            ...series,
            {
              type: 'line' as const,
              data: [],
              markLine: {
                symbol: 'none',
                silent: true,
                lineStyle: { type: 'dashed' as const },
                data: markLineData,
              },
            },
          ]
        : series,
    } as EChartsOption;
  }
}

export function TrendChart(props: TrendChartProps) {
  const { points, keys, labelOf } = props;
  const option = useMemo(() => buildTrendOption(props), [points, keys, labelOf]);
  return <Chart className="eval-trend" option={option} height={CHART_H} label="覆盖率指标趋势" />;
}

export interface ProbeCount {
  probe: string;
  severity: 'error' | 'warning';
  count: number;
}

/**
 * 探针分布：横条就是 div 宽度。
 *
 * 条子一律同色——长度表示数量，severity 由行首状态灯表达（CLAUDE.md「状态色」：
 * 三态展示只用 StatusDot，不另起一套配色）。
 */
export function ProbeBars({ rows }: { rows: ProbeCount[] }) {
  const max = Math.max(...rows.map((row) => row.count), 1);
  // 只有一个类别时不画条：一条铺满宽度的横条配一个「1」，长度不表示任何东西，
  // 没有刻度也没有对照。**条形图的信息量全在互相比较上**，没得比就退回计数行。
  const bare = rows.length < 2;
  return (
    <ul className={bare ? 'eval-bars is-bare' : 'eval-bars'}>
      {rows.map((row) => (
        <li key={row.probe}>
          <StatusDot tone={row.severity === 'error' ? 'bad' : 'warn'}
            label={row.severity === 'error' ? '硬错误' : '改进项'} />
          <code>{row.probe}</code>
          {!bare && (
            <span className="eval-bar-track">
              <span className="eval-bar-fill" style={{ width: `${(row.count / max) * 100}%` }} />
            </span>
          )}
          <b>{row.count}</b>
        </li>
      ))}
    </ul>
  );
}
