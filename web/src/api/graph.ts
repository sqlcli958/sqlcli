import { get } from './client';
import type { GraphViewDto, GraphParams } from '../types/api';

/** Fetch graph data with optional filters */
export async function getGraph(
  params: GraphParams = {},
  signal?: AbortSignal,
): Promise<GraphViewDto> {
  return get<GraphViewDto>('/graph', {
    schema: params.schema,
    table: params.table,
    depth: params.depth,
    relationType: params.relationType,
    includeIsolated: params.includeIsolated,
    maxNodes: params.maxNodes,
    maxEdges: params.maxEdges,
  }, signal);
}
