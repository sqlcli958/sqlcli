import { get } from './client';
import type { GraphChangeDto } from '../types/api';

export interface GraphChangeListDto {
  changes: GraphChangeDto[];
  total: number;
}

/**
 * 图谱变更流水：每次图谱更新一条，带上改成了什么、谁改的、是哪条审批放行的。
 *
 * `targetId` 用来看单个对象的变更历史——「这个字段的描述是谁什么时候改的」这类问题，
 * 翻整条流水找不现实。
 */
export async function listGraphChanges(
  params: { alias?: string | null; targetId?: string } = {},
  page = 0,
  pageSize = 20,
  signal?: AbortSignal,
): Promise<GraphChangeListDto> {
  return get<GraphChangeListDto>(
    '/graph-changes',
    {
      alias: params.alias ?? undefined,
      targetId: params.targetId,
      limit: pageSize,
      offset: page * pageSize,
    },
    signal,
  );
}
