import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { ApiError, post } from './client';

/**
 * token 过期自动换票。
 *
 * token 只活在服务端进程内存里，重启就换一张，而页面握着旧的——读端点不校验 token，
 * 所以页面看着一切正常，一点写操作就 403「Invalid or missing session token」。
 * 这个项目改完代码就 dev-restart，不自动换票的话每次重启页面都得手动刷新。
 */

function json(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response;
}

beforeEach(() => {
  sessionStorage.setItem('sql-cli-session-token', '旧票');
});

afterEach(() => {
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

function tokenOf(call: unknown[]): string | undefined {
  return (call[1] as RequestInit & { headers: Record<string, string> })
    .headers['X-Session-Token'];
}

test('403 UNAUTHORIZED 时换一张 token 重试，调用方看到的是成功', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(json(403, { code: 'UNAUTHORIZED', message: 'Invalid or missing session token' }))
    .mockResolvedValueOnce(json(200, { token: '新票' }))
    .mockResolvedValueOnce(json(200, { rebuilt: true }));
  vi.stubGlobal('fetch', fetchMock);

  await expect(post('/index/rebuild')).resolves.toEqual({ rebuilt: true });

  expect(fetchMock.mock.calls[1][0]).toContain('/api/session');
  expect(tokenOf(fetchMock.mock.calls[0])).toBe('旧票');
  expect(tokenOf(fetchMock.mock.calls[2])).toBe('新票');
  expect(sessionStorage.getItem('sql-cli-session-token')).toBe('新票');
});

/** 换票也失败就别装作没事——原来那个 403 要照常抛给调用方。 */
test('换票失败时把原来的 403 抛出去', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(json(403, { code: 'UNAUTHORIZED', message: 'Invalid or missing session token' }))
    .mockResolvedValueOnce(json(500, { code: 'INTERNAL', message: '挂了' }));
  vi.stubGlobal('fetch', fetchMock);

  await expect(post('/index/rebuild')).rejects.toThrow('Invalid or missing session token');
  expect(fetchMock).toHaveBeenCalledTimes(2);
});

/** 只对「票过期」重试。别的失败重试一遍既没用，还可能把一次写变成两次。 */
test('其他错误不重试', async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(json(400, { code: 'BAD_REQUEST', message: '参数不对' }));
  vi.stubGlobal('fetch', fetchMock);

  await expect(post('/index/rebuild')).rejects.toBeInstanceOf(ApiError);
  expect(fetchMock).toHaveBeenCalledTimes(1);
});
