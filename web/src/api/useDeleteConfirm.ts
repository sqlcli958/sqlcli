import { useCallback, useState } from 'react';
import type { MutationResultDto } from '../types/api';
import { onMutationSuccess } from './mutationHelpers';

/**
 * 行内「删除 → 确认 / 取消 → 删除中 → 出错」那台状态机，一份。
 *
 * 关系列表、血缘弹窗、血缘大图三处各写了一遍 confirmId / deletingId / error 加一个
 * handleDelete，逐字重复——正是 CLAUDE.md 点名过的「分页写两遍、筛选条写两遍」那类漂移。
 * 删除成功后统一走 {@link onMutationSuccess}（revision 同步 + 待审批提示），调用方只管刷新。
 */
export function useDeleteConfirm(
  run: (id: string) => Promise<MutationResultDto>,
  onDeleted?: () => void,
) {
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const ask = useCallback((id: string) => {
    setConfirmId(id);
    setError(null);
  }, []);

  const cancel = useCallback(() => {
    setConfirmId(null);
    setError(null);
  }, []);

  const confirm = useCallback(
    async (id: string) => {
      setDeletingId(id);
      setError(null);
      try {
        const result = await run(id);
        onMutationSuccess(result);
        setConfirmId(null);
        onDeleted?.();
      } catch (err: unknown) {
        setError(err instanceof Error ? err.message : '删除失败');
      } finally {
        setDeletingId(null);
      }
    },
    [run, onDeleted],
  );

  /** 给一条记录的卡片 / 行用的那组 props：是不是在确认、是不是删除中、这条的错误 */
  const stateOf = useCallback(
    (id: string) => ({
      confirming: confirmId === id,
      deleting: deletingId === id,
      error: confirmId === id ? error : null,
      onAskDelete: () => ask(id),
      onCancel: cancel,
      onConfirm: () => void confirm(id),
    }),
    [confirmId, deletingId, error, ask, cancel, confirm],
  );

  return { confirmId, deletingId, error, ask, cancel, confirm, stateOf };
}
