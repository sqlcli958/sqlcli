import { del, get, patch, post, put } from './client';
import type { AliasDetailDto, AliasDirectoryDto, AliasTestResultDto } from '../types/api';

export interface CreateAliasRequest {
  name: string;
  accessMode: 'jdbc' | 'yearning';
  dbType?: string;
  driverRef?: string;
  jdbcUrl?: string;
  /** sqlite 的连接目标：.db 文件绝对路径（策略层从这个字段读，不走 jdbcUrl 拼接） */
  database?: string;
  username?: string;
  secretRef?: string;
  secretValue?: string;
  description?: string;
  /** 默认 schema：SQL 不写前缀时用它，留空沿用 URL 里的 */
  defaultSchema?: string;
  readonly?: boolean;
  approveQuery?: boolean;
  approveUpdate?: boolean;
  approveGraph?: boolean;
  yearningHost?: string;
  yearningIdc?: string;
  yearningDatabase?: string;
  /** 仅 sqlite：文件不存在时是否允许连接时新建空库（不勾选时策略层会直接拒绝连接） */
  sqliteCreateIfMissing?: boolean;
}

export interface CreateAliasResponse {
  name: string;
  status: string;
}

export async function getAliases(signal?: AbortSignal): Promise<AliasDirectoryDto> {
  return get<AliasDirectoryDto>('/aliases', undefined, signal);
}

/**
 * 导入 / 刷新图谱。
 *
 * 给了 schema+table 就只刷这一张表，否则整库导入。两种都走 merge，
 * 人工与 Agent 维护的业务名、业务描述、语义类型、自建关系不会被覆盖；
 * 数据库注释（comment）会被同步更新。
 */
export async function importAliasGraph(
  alias: string,
  target?: { schema: string; table?: string },
): Promise<{ alias: string; status: string; message?: string }> {
  const params = new URLSearchParams();
  if (target?.schema) params.set('schema', target.schema);
  if (target?.table) params.set('table', target.table);
  const query = params.toString() ? `?${params}` : '';
  return post(`/aliases/${encodeURIComponent(alias)}/import${query}`);
}

export async function createAlias(request: CreateAliasRequest): Promise<CreateAliasResponse> {
  return post<CreateAliasResponse>('/aliases', request);
}

const aliasPath = (name: string) => `/aliases/${encodeURIComponent(name)}`;

export async function getAlias(name: string, signal?: AbortSignal): Promise<AliasDetailDto> {
  return get<AliasDetailDto>(aliasPath(name), undefined, signal);
}

/** 只发送要改的字段；未出现的字段后端保持原值。 */
export type UpdateAliasRequest = Partial<Omit<CreateAliasRequest, 'name' | 'secretValue'>>;

export async function updateAlias(name: string, request: UpdateAliasRequest): Promise<AliasDetailDto> {
  return patch<AliasDetailDto>(aliasPath(name), request);
}

export async function deleteAlias(name: string): Promise<{ name: string; status: string; note?: string }> {
  return del(aliasPath(name));
}

export async function testAlias(name: string): Promise<AliasTestResultDto> {
  return post<AliasTestResultDto>(`${aliasPath(name)}/test`);
}

export interface SqlitePathCheckResult {
  exists: boolean;
  path: string;
  message: string;
}

/**
 * sqlite 保存前的路径预检：sqlite 驱动在文件不存在时会静默新建一个空库，
 * 拼错路径要等到「一张表都没有」才会暴露。这里只查文件系统，不需要别名已存在。
 */
export async function checkSqlitePath(path: string): Promise<SqlitePathCheckResult> {
  return post<SqlitePathCheckResult>('/aliases/sqlite/check-path', { path });
}

export interface FsEntry {
  name: string;
  /** sql-cli 所在机器上的绝对路径——回填到「数据库文件路径」输入框里的就是这个值 */
  path: string;
  dir: boolean;
}

export interface FsListResult {
  /** 当前目录；null 表示这是根列表（盘符/主目录），还没进入任何具体目录 */
  path: string | null;
  /** 上一级目录；null 表示已经到根，前端应禁用「上一级」 */
  parent: string | null;
  entries: FsEntry[];
}

/**
 * 服务端目录浏览：sqlite 路径选择器用。<input type="file"> 拿不到绝对路径，
 * 只能服务端自己列目录。仅在 UI 绑定回环地址时可用，否则后端返回 403。
 */
export async function listServerDirectory(path?: string, signal?: AbortSignal): Promise<FsListResult> {
  return get<FsListResult>('/fs/list', path ? { path } : undefined, signal);
}

/** 密码只进不出：value 不入 store、不缓存，响应体也不含它。 */
export async function setAliasSecret(name: string, value: string): Promise<{ name: string; secretRef: string; status: string }> {
  return put(`${aliasPath(name)}/secret`, { value });
}
