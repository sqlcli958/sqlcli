import { get } from './client';

export interface ImpactItemDto {
  kind: 'metric' | 'term' | 'lineage' | 'relation' | string;
  id: string;
  label?: string | null;
  dependency: string;
  status?: string | null;
  breaking: boolean;
}

export interface ImpactReportDto {
  targetId: string;
  total: number;
  breaking: number;
  impacts: ImpactItemDto[];
}

/** 变更前依赖分析：只返回结构化、可证明的引用，不从自由文本里猜依赖。 */
export async function getImpact(targetId: string, signal?: AbortSignal): Promise<ImpactReportDto> {
  return get<ImpactReportDto>('/impact', { targetId }, signal);
}
