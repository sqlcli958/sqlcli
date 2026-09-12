import { get, post } from './client';
import type { IndexStatusDto, IndexRebuildDto } from '../types/api';

/** Get index build status */
export async function getIndexStatus(signal?: AbortSignal): Promise<IndexStatusDto> {
  return get<IndexStatusDto>('/index/status', undefined, signal);
}

/** Rebuild the search index */
export async function rebuildIndex(signal?: AbortSignal): Promise<IndexRebuildDto> {
  return post<IndexRebuildDto>('/index/rebuild', undefined, signal);
}
