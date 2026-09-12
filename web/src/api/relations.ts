import { get, post, patch, del } from './client';
import type {
  RelationEdgeDto,
  RelationCreateDto,
  RelationPatchDto,
  MutationResultDto,
} from '../types/api';

/** Get a relation by ID */
export async function getRelation(
  relationId: string,
  signal?: AbortSignal,
): Promise<RelationEdgeDto> {
  return get<RelationEdgeDto>(`/relations/${encodeURIComponent(relationId)}`, undefined, signal);
}

/** Create a new relation */
export async function createRelation(
  body: RelationCreateDto,
  signal?: AbortSignal,
): Promise<MutationResultDto> {
  return post<MutationResultDto>('/relations', body, signal);
}

/** Patch a relation */
export async function patchRelation(
  relationId: string,
  body: RelationPatchDto,
  signal?: AbortSignal,
): Promise<MutationResultDto> {
  return patch<MutationResultDto>(`/relations/${encodeURIComponent(relationId)}`, body, signal);
}

/** Publish a candidate relation (status -> verified/partial) */
export async function publishRelation(
  relationId: string,
  expectedRevision: number,
  reason?: string,
  signal?: AbortSignal,
): Promise<MutationResultDto> {
  return post<MutationResultDto>(
    `/relations/${encodeURIComponent(relationId)}/publish`,
    { expectedRevision, reason },
    signal,
  );
}

/** Reject a candidate relation (status -> ignored, reason recorded; edge stays in the graph) */
export async function rejectRelation(
  relationId: string,
  expectedRevision: number,
  reason?: string,
  signal?: AbortSignal,
): Promise<MutationResultDto> {
  return post<MutationResultDto>(
    `/relations/${encodeURIComponent(relationId)}/reject`,
    { expectedRevision, reason },
    signal,
  );
}

/** Undo a rejection (status: ignored -> candidate) */
export async function unignoreRelation(
  relationId: string,
  expectedRevision: number,
  reason?: string,
  signal?: AbortSignal,
): Promise<MutationResultDto> {
  return post<MutationResultDto>(
    `/relations/${encodeURIComponent(relationId)}/unignore`,
    { expectedRevision, reason },
    signal,
  );
}

export interface BatchRelationReviewResultDto {
  results: { relationId: string; success: boolean; error?: string }[];
  succeeded: number;
  failed: number;
  newRevision: number;
}

/** Publish or reject a batch of candidate relations in one request. */
export async function batchReviewRelations(
  action: 'publish' | 'reject',
  relationIds: string[],
  reason?: string,
  signal?: AbortSignal,
): Promise<BatchRelationReviewResultDto> {
  return post<BatchRelationReviewResultDto>('/relations/review', { action, relationIds, reason }, signal);
}

/** Delete a relation */
export async function deleteRelation(
  relationId: string,
  expectedRevision: number,
  reason?: string,
  signal?: AbortSignal,
): Promise<MutationResultDto> {
  return del<MutationResultDto>(
    `/relations/${encodeURIComponent(relationId)}`,
    { expectedRevision, reason },
    signal,
  );
}

export interface RelationListDto {
  relations: RelationEdgeDto[];
  revision: number;
}

/**
 * 按状态列出整个数据源的关系。评审页的图谱标签用它拿候选队列——
 * 图谱页是按表给关系的，凑齐待评审的边得翻遍每张表。
 */
export async function listRelations(
  status: string,
  signal?: AbortSignal,
): Promise<RelationListDto> {
  return get<RelationListDto>('/relations', { status }, signal);
}
