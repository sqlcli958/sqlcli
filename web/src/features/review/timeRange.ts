/**
 * 审批记录和执行记录共用的时间范围选项。
 *
 * 用预设区间而不是两个日期框：这两个列表都是「最近怎么样了」的视角，
 * 挑一个精确到分钟的区间没人用。真需要了再换成 datetime-local。
 */
export const TIME_RANGES: { key: string; label: string; ms: number | null }[] = [
  { key: 'all', label: '全部时间', ms: null },
  { key: '1h', label: '最近 1 小时', ms: 3600_000 },
  { key: '24h', label: '最近 24 小时', ms: 86_400_000 },
  { key: '7d', label: '最近 7 天', ms: 7 * 86_400_000 },
  { key: '30d', label: '最近 30 天', ms: 30 * 86_400_000 },
];

export function rangeMs(key: string): number | null {
  return TIME_RANGES.find((item) => item.key === key)?.ms ?? null;
}

/**
 * 区间起点的时间戳；`all` 返回 undefined。
 *
 * 一定要在取数那一刻算，不能存进 state——存了绝对时间戳，页面开着不动就会越查越旧。
 */
export function since(key: string): number | undefined {
  const ms = rangeMs(key);
  return ms ? Date.now() - ms : undefined;
}
