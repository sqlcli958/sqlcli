/**
 * 一级导航配置。
 *
 * 页面按用户任务分成三个视觉分组：Work / Knowledge / Governance；
 * 设置固定在底部。分组只影响信息架构和视觉层级，不改现有路由。
 */
export type NavGroup = 'work' | 'knowledge' | 'governance' | 'system';

export interface NavItem {
  /** 路由末段，拼在 /workspaces/local/ 之后 */
  key: string;
  label: string;
  /** 视觉分组。 */
  group: NavGroup;
  /** 24×24 viewBox 的 SVG path。导航收起后只剩它，所以每一项都必须有 */
  icon: string;
  /** 需要选中数据源才可用 */
  needsAlias: boolean;
}

export const WORKSPACE_BASE = '/workspaces/local';

export const NAV_GROUP_LABELS: Record<NavGroup, string> = {
  work: '工作',
  knowledge: '知识',
  governance: '治理',
  system: '系统',
};

/**
 * 24×24 图标，描边而非填充。
 * 收起后这就是这一项的全部，描边线条在 16~17px 更容易区分。
 */
const ICONS = {
  workbench: 'M3 5h18v14H3z M7 10l2 2-2 2 M13 15h4',
  graph: 'M6 4.5a2.5 2.5 0 110 5 2.5 2.5 0 010-5z M18 4.5a2.5 2.5 0 110 5 2.5 2.5 0 010-5z '
    + 'M12 14.5a2.5 2.5 0 110 5 2.5 2.5 0 010-5z M8.5 7h7 M7.4 9.2l3.2 4.1 M16.6 9.2l-3.2 4.1',
  rules: 'M7 3h7l4 4v14H7z M14 3v4h4 M10 12h5 M10 16h3',
  review: 'M12 3.5a8.5 8.5 0 110 17 8.5 8.5 0 010-17z M8.4 12.2l2.6 2.6 4.9-4.9',
  evaluation: 'M4 4v15.5h16 M7.5 15l3.5-4.5 3 2.5L20 7',
  metrics: 'M4 4v15.5h16 M7.5 19.5v-6 M12 19.5v-10 M16.5 19.5v-4',
  settings: 'M12 8.5a3.5 3.5 0 110 7 3.5 3.5 0 010-7z M12 2.5v2.5 M12 19v2.5 M2.5 12H5 M19 12h2.5 '
    + 'M5.2 5.2l1.8 1.8 M17 17l1.8 1.8 M18.8 5.2L17 7 M7 17l-1.8 1.8',
} as const;

export const NAV_ITEMS: NavItem[] = [
  { key: 'sql', label: '工作台', group: 'work', icon: ICONS.workbench, needsAlias: false },
  { key: 'knowledge', label: '图谱', group: 'knowledge', icon: ICONS.graph, needsAlias: true },
  { key: 'metrics', label: '指标', group: 'knowledge', icon: ICONS.metrics, needsAlias: true },
  { key: 'eval', label: '评估', group: 'knowledge', icon: ICONS.evaluation, needsAlias: true },
  { key: 'rules', label: '规则', group: 'governance', icon: ICONS.rules, needsAlias: true },
  { key: 'reviews', label: '评审', group: 'governance', icon: ICONS.review, needsAlias: false },
  { key: 'settings', label: '设置', group: 'system', icon: ICONS.settings, needsAlias: false },
];

/** 带上当前数据源构造一级页面链接。 */
export function navPath(key: string, alias: string | null): string {
  const base = `${WORKSPACE_BASE}/${key}`;
  return alias ? `${base}?alias=${encodeURIComponent(alias)}` : base;
}
