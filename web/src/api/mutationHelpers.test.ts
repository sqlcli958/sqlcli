import { beforeEach, expect, test, vi } from 'vitest';
import { onMutationSuccess } from './mutationHelpers';
import { useSessionStore } from '../state/sessionStore';
import { queryClient } from './queryClient';

beforeEach(() => {
  useSessionStore.setState({ revision: 0, pendingApproval: null });
  vi.spyOn(queryClient, 'invalidateQueries').mockReturnValue(Promise.resolve());
});

test('普通写入推进 revision，不留待审批提示', () => {
  onMutationSuccess({ newRevision: 7, changeId: 'c7' });

  expect(useSessionStore.getState().revision).toBe(7);
  expect(useSessionStore.getState().pendingApproval).toBeNull();
});

// 开了图谱审批时这次写入没有生效，只是排队了。不在这个唯一的收口处挂出来，
// 编辑器一关就像存成功了，而图谱纹丝不动——这是整条链路上唯一会骗到人的地方。
test('进了审批队列时挂出审批号供顶栏提示', () => {
  onMutationSuccess({ newRevision: 5, changeId: '', pendingApprovalId: 42 });

  expect(useSessionStore.getState().pendingApproval).toBe(42);

  useSessionStore.getState().dismissPendingApproval();
  expect(useSessionStore.getState().pendingApproval).toBeNull();
});
