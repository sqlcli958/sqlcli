import { del, get, patch, post, postBinary, put } from './client';

export interface DriverDto {
  name: string;
  dbType?: string;
  driverClass?: string;
  jars: string[];
  isDefault: boolean;
  /** 引用该驱动的数据源名 */
  usedBy: string[];
  /** ok=jar 可用 / warn=jar 在但类加载失败 / bad=文件不存在 */
  status: 'ok' | 'warn' | 'bad';
  statusDetail: string;
}

export interface DriverPayload {
  name?: string;
  dbType?: string;
  driverClass?: string;
  jars?: string[];
}

/** 按 dbType 预填的驱动类名，与后端 DriverAdminController 保持一致。 */
export const DEFAULT_DRIVER_CLASS: Record<string, string> = {
  mysql: 'com.mysql.cj.jdbc.Driver',
  postgresql: 'org.postgresql.Driver',
  oracle: 'oracle.jdbc.OracleDriver',
  clickhouse: 'com.clickhouse.jdbc.ClickHouseDriver',
};

/**
 * 上传驱动 jar。
 *
 * 直接把文件字节 POST 过去，文件名走 query——Java 内置 HttpServer 没有 multipart 解析，
 * 为传一个文件手写一个不划算。返回可直接写进配置的相对路径。
 */
export async function uploadDriverJar(
  dbType: string,
  file: File,
): Promise<{ path: string; size: number; fileName: string }> {
  const query = `?dbType=${encodeURIComponent(dbType)}&filename=${encodeURIComponent(file.name)}`;
  return postBinary(`/drivers/upload${query}`, file);
}

export async function getDrivers(signal?: AbortSignal): Promise<DriverDto[]> {
  return get('/drivers', undefined, signal);
}

export async function createDriver(payload: DriverPayload): Promise<{ name: string; status: string }> {
  return post('/drivers', payload);
}

export async function updateDriver(
  name: string,
  payload: DriverPayload,
): Promise<{ name: string; status: string }> {
  return patch(`/drivers/${encodeURIComponent(name)}`, payload);
}

export async function deleteDriver(name: string): Promise<{ name: string; status: string }> {
  return del(`/drivers/${encodeURIComponent(name)}`);
}

export async function setDefaultDriver(name: string): Promise<{ name: string; status: string }> {
  return put(`/drivers/${encodeURIComponent(name)}/default`, {});
}
