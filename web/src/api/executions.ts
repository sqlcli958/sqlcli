import { get, post } from './client';
import type { RecoveryPreviewDto, SqlExecutionRecordDto } from '../types/api';

/** 执行记录的四个筛选条件。全部可选，留空即不过滤。 */
export interface ExecutionFilters {
  /** 语句命中的 schema */
  schema?: string;
  /** 语句类型：SELECT / UPDATE / DELETE … */
  type?: string;
  /** 执行状态：success / failed / rejected */
  status?: string;
  /** 时间范围（毫秒时间戳），左闭右开 */
  startedAfter?: number;
  startedBefore?: number;
}

/**
 * 执行记录分页。total 和 schema 选项跟着列表一起回来，
 * 前者算总页数，后者当筛选器的下拉项——都不值得单独一个端点。
 */
export interface ExecutionListDto {
  records: SqlExecutionRecordDto[];
  /** 当前筛选条件下的总条数，算总页数用 */
  total: number;
  /** 这个数据源历史里出现过的 schema，筛选器的选项 */
  schemas: string[];
}

export async function getExecutionHistory(
  alias: string,
  page = 0,
  pageSize = 20,
  filters: ExecutionFilters = {},
  signal?: AbortSignal,
): Promise<ExecutionListDto> {
  const body = await get<ExecutionListDto>(
    '/executions',
    { alias, limit: pageSize, offset: page * pageSize, ...filters },
    signal,
  );
  return { ...body, schemas: body.schemas ?? [] };
}

/** 回滚 SQL 与执行前的原始行。没有回滚脚本的记录会 404。 */
export async function getRecoveryPreview(
  executionId: number,
  signal?: AbortSignal,
): Promise<RecoveryPreviewDto> {
  return get(`/executions/${executionId}/recovery`, undefined, signal);
}

/**
 * 真正执行恢复脚本（单连接单事务，全部成功才提交）。回滚永远要审批
 * （kind=recovery 无开关），请求会挂住直到评审页裁决，和工作台写审批同一个模式，
 * 所以这里不设超时——交给调用方的 AbortSignal。
 */
/**
 * 提交回滚，**不执行**：服务端只排一条待审批就返回，真正执行发生在审批中心批准的那一刻。
 */
export async function executeRollback(
  executionId: number,
  signal?: AbortSignal,
): Promise<{ executionId: number; statements: number; approvalId: number; status: string }> {
  return post(`/executions/${executionId}/rollback`, {}, signal);
}
