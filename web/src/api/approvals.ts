import { get, post } from './client';
import type {
  ApprovalBatchDto,
  ApprovalDetailDto,
  ApprovalDto,
  ApprovalKind,
  ApprovalStatus,
} from '../types/api';

export interface ApprovalListDto {
  approvals: ApprovalDto[];
  /** 这一页里出现过的批次，随列表一起返回（免得再对齐一次分页） */
  batches?: ApprovalBatchDto[];
  /** 当前筛选条件下的总条数，算总页数用 */
  total: number;
  /** 待审批总数，角标用，和筛选条件无关 */
  pending: number;
}

export interface ApprovalFilters {
  /** 'all' 表示不过滤 */
  status?: ApprovalStatus | 'all';
  kind?: ApprovalKind | 'all';
  /** 排除这个类型。「审批记录」传 'graph'：图谱的历史在图谱标签里 */
  excludeKind?: ApprovalKind;
  /** 排除这个状态。「图谱」标签传 'pending'：还没裁决的在待审批标签里 */
  excludeStatus?: ApprovalStatus;
  /** 只看这个数据源；不传表示跨数据源 */
  alias?: string;
  /** 只看这个时间点之后创建的（毫秒时间戳） */
  createdAfter?: number;
}

/**
 * 审批列表。
 *
 * 类型筛选走服务端而不是前端过滤一页数据：分页之后「当前页里符合类型的那几条」
 * 和「符合类型的第 N 页」是两回事。
 */
export async function getApprovals(
  filters: ApprovalFilters = {},
  page = 0,
  pageSize = 20,
  signal?: AbortSignal,
): Promise<ApprovalListDto> {
  return get(
    '/approvals',
    {
      status: filters.status ?? 'pending',
      kind: filters.kind,
      excludeKind: filters.excludeKind,
      excludeStatus: filters.excludeStatus,
      alias: filters.alias,
      createdAfter: filters.createdAfter,
      limit: pageSize,
      offset: page * pageSize,
    },
    signal,
  );
}

/** 详情比列表重（要读 task_event），只在展开卡片时拉。 */
export async function getApprovalDetail(
  id: number,
  signal?: AbortSignal,
): Promise<ApprovalDetailDto> {
  return get(`/approvals/${id}`, undefined, signal);
}

export async function decideApproval(
  id: number,
  decision: 'approved' | 'rejected',
  reason?: string,
): Promise<ApprovalDto> {
  return post(`/approvals/${id}/decide`, { decision, reason });
}

/**
 * 整批裁决。批准 = 落地这一批里所有没被单独否掉的条目，原子。
 *
 * 逐条「否掉这条」仍然走 {@link decideApproval}——那是裁决单位，这是审批单位。
 */
export async function decideBatch(
  id: number,
  decision: 'approved' | 'rejected',
  reason?: string,
): Promise<ApprovalBatchDto & { items: ApprovalDto[] }> {
  return post(`/batches/${id}/decide`, { decision, reason });
}
