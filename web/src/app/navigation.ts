/**
 * 一级导航配置。
 *
 * 七个页面（原六项破例加了「指标」，理由见 CLAUDE.md「一级导航」一节）。
 * 原「概览」并入工作台（没选数据源时它就是数据源目录），
 * 原「运行记录」并入工作台的执行记录分区——都只有一份内容，
 * 分成两页只是让人多点一次。
 *
 * 「评估」是第六项，理由见 CLAUDE.md：它是唯一回答「图谱这次比上次好了还是坏了」
 * 的页面，而规则页答的是「库设计得对不对」。
 */
export interface NavItem {
  /** 路由末段，拼在 /workspaces/local/ 之后 */
  key: string;
  label: string;
  /** 24×24 viewBox 的 SVG path。导航收起后只剩它，所以每一项都必须有 */
  icon: string;
  /** 需要选中数据源才可用 */
  needsAlias: boolean;
}

export const WORKSPACE_BASE = '/workspaces/local';

/**
 * 24×24 图标，描边而非填充。
 *
 * 收起后这就是这一项的全部，而实心图形缩到 16px 会糊成一个色块——
 * 描边线条在这个尺寸上才分得清谁是谁。渲染方式见 AppShell 的 `.shell-nav-icon`。
 */
const ICONS = {
  /** 终端窗口 */
  workbench: 'M3 5h18v14H3z M7 10l2 2-2 2 M13 15h4',
  /** 三节点连线 */
  graph: 'M6 4.5a2.5 2.5 0 110 5 2.5 2.5 0 010-5z M18 4.5a2.5 2.5 0 110 5 2.5 2.5 0 010-5z '
    + 'M12 14.5a2.5 2.5 0 110 5 2.5 2.5 0 010-5z M8.5 7h7 M7.4 9.2l3.2 4.1 M16.6 9.2l-3.2 4.1',
  /** 条目清单 */
  rules: 'M7 3h7l4 4v14H7z M14 3v4h4 M10 12h5 M10 16h3',
  /** 打勾的圆 */
  review: 'M12 3.5a8.5 8.5 0 110 17 8.5 8.5 0 010-17z M8.4 12.2l2.6 2.6 4.9-4.9',
  /** 坐标轴上的一条折线 */
  evaluation: 'M4 4v15.5h16 M7.5 15l3.5-4.5 3 2.5L20 7',
  /** 坐标轴 + 三根柱：口径展开成的是一串按时间分桶的数，不是一条连续曲线的走势判断——
   * 跟 evaluation 的折线图标故意画成不同形状，收起导航后仍分得出谁是谁 */
  metrics: 'M4 4v15.5h16 M7.5 19.5v-6 M12 19.5v-10 M16.5 19.5v-4',
  /** 齿轮 */
  settings: 'M12 8.5a3.5 3.5 0 110 7 3.5 3.5 0 010-7z M12 2.5v2.5 M12 19v2.5 M2.5 12H5 M19 12h2.5 '
    + 'M5.2 5.2l1.8 1.8 M17 17l1.8 1.8 M18.8 5.2L17 7 M7 17l-1.8 1.8',
} as const;

export const NAV_ITEMS: NavItem[] = [
  { key: 'sql', label: '工作台', icon: ICONS.workbench, needsAlias: false },
  // 没有图谱也要能进：导入图谱的唯一入口就在这一页的表目录里，
  // 按 needsGraph 禁用会把新数据源锁在门外——没图谱进不去，进不去导不了图谱。
  { key: 'knowledge', label: '图谱', icon: ICONS.graph, needsAlias: true },
  // 第七项，破例见 CLAUDE.md「一级导航」一节：三层模型里口径层在语义层之上、
  // 消费下面两层，挨着图谱放提示它的定义来自图谱图对象；用法是「定义口径→展开
  // SQL→出图看走势」，跟图谱页「按 schema/表浏览图谱对象」不是同一种活，
  // 塞进图谱页第四个标签是上一版放错的位置。
  { key: 'metrics', label: '指标', icon: ICONS.metrics, needsAlias: true },
  { key: 'rules', label: '规则', icon: ICONS.rules, needsAlias: true },
  // 评估结果按 alias + revision 存，跨数据源看没有意义（两个库的覆盖率不可比）
  { key: 'eval', label: '评估', icon: ICONS.evaluation, needsAlias: true },
  // 评审跨数据源看：CLI 在别的库上等审批时，不该因为顶栏选中的是另一个别名就看不见。
  { key: 'reviews', label: '评审', icon: ICONS.review, needsAlias: false },
  { key: 'settings', label: '设置', icon: ICONS.settings, needsAlias: false },
];

/** 带上当前数据源构造一级页面链接。 */
export function navPath(key: string, alias: string | null): string {
  const base = `${WORKSPACE_BASE}/${key}`;
  return alias ? `${base}?alias=${encodeURIComponent(alias)}` : base;
}
