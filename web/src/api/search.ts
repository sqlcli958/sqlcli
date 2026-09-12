import { get } from './client';
import type { SearchParams, SearchResponseDto } from '../types/api';

/** Search tables and columns */
export async function search(
  params: SearchParams,
  signal?: AbortSignal,
): Promise<SearchResponseDto> {
  return get<SearchResponseDto>('/search', {
    q: params.q,
    type: params.type,
    limit: params.limit,
  }, signal);
}
