import type { MutationResultDto } from '../types/api';
import { queryClient } from './queryClient';
import { useGraphStore } from '../state/graphStore';
import { useSessionStore } from '../state/sessionStore';

/**
 * Call after any successful mutation (PATCH table, PATCH column,
 * POST/PATCH/DELETE relation) to maintain write-then-read consistency.
 *
 * 1. Updates workspaceRevision in graphStore from MutationResultDto.newRevision
 * 2. Updates revision in sessionStore to keep them in sync
 * 3. Invalidates react-query caches so fresh data is fetched
 */
export function onMutationSuccess(result: MutationResultDto): void {
  // Update revision in both stores
  useGraphStore.getState().setWorkspaceRevision(result.newRevision);
  useSessionStore.setState({ revision: result.newRevision });

  // 开了图谱审批时这次写入没有生效，只是排进了审批队列（revision 也没动）。
  // 十个调用点各自弹一次提示不现实，所以在这个唯一的收口处挂到顶栏说一句——
  // 不说的话编辑器一关就像存成功了，而图谱纹丝不动。
  if (result.pendingApprovalId != null) {
    useSessionStore.setState({ pendingApproval: result.pendingApprovalId });
  }

  // Invalidate all queries so fresh data is fetched
  queryClient.invalidateQueries();
}
