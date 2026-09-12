import { get, post } from './client';
import type { MetricDto, MetricSqlDto, MetricUpsertRequest, MetricsResponseDto } from '../types/api';

/**
 * F4b/F4a 的字段还没进 `types/api.ts`（那份文件这次改动不碰，避免跟同时在改它的其他工作
 * 冲突）——本地扩展 `MetricDto`/`MetricUpsertRequest`，对多出来的可选字段结构性兼容，
 * 等 api.ts 那边真正补上这几个字段时把这两个类型删掉，改回直接用 types/api 的定义。
 */
export interface MetricComponentDto {
  /** 聚合表达式，如 COUNT(DISTINCT user_id) */
  expression: string;
  /** 只作用于这一侧的过滤条件，不影响另一侧 */
  filters?: string | null;
}

export type MetricAdditivity = 'additive' | 'semi_additive' | 'non_additive';

export interface RatioMetricDto extends MetricDto {
  numerator?: MetricComponentDto | null;
  denominator?: MetricComponentDto | null;
  additivity?: MetricAdditivity | null;
}

export type MetricUpsertRequestWithRatio = Omit<MetricUpsertRequest, 'expression'> & {
  /** 比率指标（声明了 numerator/denominator）不需要顶层 expression。 */
  expression?: string;
  numerator?: MetricComponentDto;
  denominator?: MetricComponentDto;
  additivity?: MetricAdditivity;
};

/** BI 指标列表（口径声明，不是数据）。 */
export async function getMetrics(signal?: AbortSignal): Promise<MetricsResponseDto> {
  return get<MetricsResponseDto>('/metrics', undefined, signal);
}

/** 建或改一条指标，按 name 幂等 upsert。 */
export async function upsertMetric(
  body: MetricUpsertRequestWithRatio,
): Promise<{ newRevision: number; metricId: string }> {
  return post('/metrics', body);
}

/**
 * 展开成可执行 SQL。纯只读预览——服务端不连库也不落库，方言取图谱里记着的 dbType。
 */
export async function expandMetricSql(
  name: string,
  params: { grain?: string; dimensions?: string; timeFrom?: string; timeTo?: string },
  signal?: AbortSignal,
): Promise<MetricSqlDto> {
  return get<MetricSqlDto>(`/metrics/${encodeURIComponent(name)}/sql`, params, signal);
}

/**
 * `column:alias:schema.table.column` → `schema.table.column`。
 *
 * 服务端原样返回 id 不翻译，因为翻译就是这一行；让服务端也做一份等于多一种要同步的形态。
 */
export function columnRef(columnId: string): string {
  const parts = columnId.split(':');
  return parts.length < 3 ? columnId : parts.slice(2).join(':');
}

export type { MetricDto };
