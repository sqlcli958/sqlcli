import type { ApiErrorBody } from '../types/api';

const BASE_URL = '/api';

/** Custom API error */
export class ApiError extends Error {
  public readonly status: number;
  public readonly body: ApiErrorBody;

  constructor(status: number, body: ApiErrorBody) {
    super(body.message || `API error ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

/** Get the current session token */
function getToken(): string | null {
  return sessionStorage.getItem('sql-cli-session-token');
}

function apiUrl(path: string, params?: Record<string, string | number | boolean | undefined>): URL {
  const url = new URL(`${BASE_URL}${path}`, window.location.origin);
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value !== undefined) url.searchParams.set(key, String(value));
  });
  // 地址栏的 alias 自动带上，但显式传的优先——评审页的待审批列表要故意跨数据源查。
  const alias = new URLSearchParams(window.location.search).get('alias');
  if (alias && !url.searchParams.has('alias')) url.searchParams.set('alias', alias);
  return url;
}

/** Build headers with optional auth */
function headers(): Record<string, string> {
  const h: Record<string, string> = {
    'Accept': 'application/json',
  };
  const token = getToken();
  if (token) {
    h['X-Session-Token'] = token;
  }
  return h;
}

/** Parse an error response */
async function parseError(res: Response): Promise<ApiError> {
  let body: ApiErrorBody;
  try {
    body = await res.json();
  } catch {
    body = {
      code: String(res.status),
      message: `Request failed with status ${res.status}`,
    };
  }
  return new ApiError(res.status, body);
}

/**
 * 所有请求的唯一出口：拼 URL、带 token、非 2xx 抛 {@link ApiError}，
 * 并且**token 过期时自动换一张重试一次**。
 *
 * 六个动词原来各写一遍 fetch + `if (!res.ok) throw`，重试要加六遍——
 * 加六遍的下场是慢慢只剩三遍还对。
 */
async function request<T>(
  method: string,
  path: string,
  opts: {
    params?: Record<string, string | number | boolean | undefined>;
    body?: BodyInit;
    contentType?: string;
    signal?: AbortSignal;
  } = {},
): Promise<T> {
  const url = apiUrl(path, opts.params).toString();
  const send = () => {
    const h = headers();
    if (opts.contentType) h['Content-Type'] = opts.contentType;
    return fetch(url, { method, headers: h, body: opts.body, signal: opts.signal });
  };

  let res = await send();
  if (res.status === 403) {
    const error = await parseError(res);
    // token 只在服务端内存里（GraphUiSession 构造时随机生成），进程一重启就换一个，
    // 而页面握着旧的那张——读端点不校验 token，所以页面看着好好的，一点写操作就 403。
    // dev-restart.ps1 是这个项目的常规动作，不自动换票的话每次改完代码页面就变砖一次。
    if (error.body.code !== 'UNAUTHORIZED' || !(await refreshSessionToken())) {
      throw error;
    }
    // 重试是安全的：403 是在做任何事之前就拒了，不存在写了一半又跑一遍
    res = await send();
  }
  if (!res.ok) {
    throw await parseError(res);
  }
  return res.json() as Promise<T>;
}

/**
 * 重新握手换一张 token。
 *
 * 直接 fetch 而不是走 `api/session.ts`：那个模块 import 本模块，反过来引会成环。
 * 并发的写请求可能同时撞进来各握一次手——服务端是 `computeIfAbsent`，
 * 拿到的是同一张票，重复几次没有代价，不值得为它加一层去重。
 *
 * @return 换到新 token 了吗；没换到就让原来那个 403 照常抛出去
 */
async function refreshSessionToken(): Promise<boolean> {
  try {
    const res = await fetch(apiUrl('/session').toString(), {
      method: 'GET',
      headers: { Accept: 'application/json' },
    });
    if (!res.ok) return false;
    const body = (await res.json()) as { token?: string };
    if (!body.token) return false;
    sessionStorage.setItem('sql-cli-session-token', body.token);
    return true;
  } catch {
    return false;
  }
}

/** Generic GET */
export async function get<T>(
  path: string,
  params?: Record<string, string | number | boolean | undefined>,
  signal?: AbortSignal,
): Promise<T> {
  return request<T>('GET', path, { params, signal });
}

/** Generic POST */
export async function post<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  return request<T>('POST', path, {
    body: body ? JSON.stringify(body) : undefined,
    contentType: 'application/json',
    signal,
  });
}

/** Generic PATCH */
export async function patch<T>(path: string, body: unknown, signal?: AbortSignal): Promise<T> {
  return request<T>('PATCH', path, {
    body: JSON.stringify(body),
    contentType: 'application/json',
    signal,
  });
}

/**
 * 上传原始字节（当前只有驱动 jar 用）。
 *
 * 不设 Content-Type 让浏览器自己带 application/octet-stream，
 * 后端直接读 InputStream，不用解析 multipart。
 */
export async function postBinary<T>(path: string, file: Blob, signal?: AbortSignal): Promise<T> {
  return request<T>('POST', path, { body: file, signal });
}

/** Generic PUT */
export async function put<T>(path: string, body: unknown, signal?: AbortSignal): Promise<T> {
  return request<T>('PUT', path, {
    body: JSON.stringify(body),
    contentType: 'application/json',
    signal,
  });
}

/** Generic DELETE */
export async function del<T>(
  path: string,
  params?: Record<string, string | number | undefined>,
  signal?: AbortSignal,
): Promise<T> {
  return request<T>('DELETE', path, { params, signal });
}
