import { post } from './client';
import type { WorkbenchExecuteResultDto } from '../types/api';

/**
 * 工作台执行。alias 由 client 自动带上（沿用地址栏的 ?alias=）。
 *
 * `dryRun` 只跑预检，用来在写操作的确认面板上摆出影响行数和可恢复性。
 *
 * **不设超时**：别名开了审批时，这个请求会一直挂到有人在评审页裁决为止（后端上限
 * 10 分钟），前端把它显示成「等待审批中」。主动断开只会让页面失去结果，那条审批
 * 请求还在评审页排着，用户反而以为没提交成功。
 */
export async function executeWorkbenchSql(
  sql: string,
  dryRun = false,
  cancelToken?: string,
  signal?: AbortSignal,
): Promise<WorkbenchExecuteResultDto> {
  return post('/workbench/execute', { sql, dryRun, cancelToken }, signal);
}

/**
 * 中止一次执行。`cancelled=false` 表示那次执行已经结束（或从未开始），
 * 不是错误——调用方据此决定要不要把结果标成「已中止」。
 */
export async function cancelWorkbenchSql(
  cancelToken: string,
): Promise<{ cancelled: boolean }> {
  return post('/workbench/cancel', { cancelToken });
}
