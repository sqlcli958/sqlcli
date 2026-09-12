import { post } from './client';
import type { SqlExecuteResultDto } from '../types/api';

/** 执行只读 SQL。alias 由 client 自动带上（沿用地址栏的 ?alias=）。 */
export async function executeSql(sql: string, signal?: AbortSignal): Promise<SqlExecuteResultDto> {
  return post('/sql/execute', { sql }, signal);
}
