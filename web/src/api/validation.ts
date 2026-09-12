import { get } from './client';
import type { ValidationIssuesResponseDto } from '../types/api';

/** Get validation issues, optionally filtered */
export async function getValidationIssues(
  targetId?: string,
  severity?: string,
  signal?: AbortSignal,
): Promise<ValidationIssuesResponseDto> {
  return get<ValidationIssuesResponseDto>('/validation/issues', { targetId, severity }, signal);
}
