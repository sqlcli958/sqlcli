/**
 * 一级导航只表达产品级任务，不把每个功能都抬成一级入口。
 *
 * 工作台 = 写/执行 SQL
 * 数据模型 = 表、关系、血缘、术语
 * 指标 = 业务指标
 * 治理 = 质量、规则、审批与审计
 * 设置 = 数据源与运行时配置
 */
export type NavGroup = 'primary' | 'system';

export interface NavItem {
  /** 路由末段，拼在 /workspaces/local/ 之后 */
  key: string;
  label: string;
  group: NavGroup;
  /** 24×24 viewBox 的 SVG path */
  icon: string;
  /** 需要选中数据源才可用 */
  needsAlias: boolean;
}

export const WORKSPACE_BASE = '/workspaces/local';

export const NAV_GROUP_LABELS: Record<NavGroup, string> = {
  primary: 'WORKSPACE',
  system: 'SYSTEM',
};

const ICONS = {
  workbench: 'M3 5h18v14H3z M7 10l2 2-2 2 M13 15h4',
  model: 'M5 4h5v5H5z M14 4h5v5h-5z M9.5 6.5h5 M7.5 9v4.5 M16.5 9v4.5 M5 14h5v5H5z M14 14h5v5h-5z M10 16.5h4',
  metrics: 'M4 4v15.5h16 M7.5 19.5v-6 M12 19.5v-10 M16.5 19.5v-4',
  governance: 'M12 3l7 3v5c0 4.6-2.8 8.2-7 10-4.2-1.8-7-5.4-7-10V6l7-3z M9 12l2 2 4-4',
  settings: 'M12 8.5a3.5 3.5 0 110 7 3.5 3.5 0 010-7z M12 2.5v2.5 M12 19v2.5 M2.5 12H5 M19 12h2.5 '
    + 'M5.2 5.2l1.8 1.8 M17 17l1.8 1.8 M18.8 5.2L17 7 M7 17l-1.8 1.8',
} as const;

export const NAV_ITEMS: NavItem[] = [
  { key: 'sql', label: '工作台', group: 'primary', icon: ICONS.workbench, needsAlias: false },
  { key: 'knowledge', label: '数据模型', group: 'primary', icon: ICONS.model, needsAlias: true },
  { key: 'metrics', label: '指标', group: 'primary', icon: ICONS.metrics, needsAlias: true },
  { key: 'governance', label: '治理', group: 'primary', icon: ICONS.governance, needsAlias: false },
  { key: 'settings', label: '设置', group: 'system', icon: ICONS.settings, needsAlias: false },
];

/** 带上当前数据源和可选页面参数构造 workspace 链接。 */
export function navPath(
  key: string,
  alias: string | null,
  params?: Record<string, string | undefined | null>,
): string {
  const query = new URLSearchParams();
  if (alias) query.set('alias', alias);
  Object.entries(params ?? {}).forEach(([name, value]) => {
    if (value != null && value !== '') query.set(name, value);
  });
  const suffix = query.toString();
  return `${WORKSPACE_BASE}/${key}${suffix ? `?${suffix}` : ''}`;
}
