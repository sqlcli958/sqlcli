import { get } from './client';
import type { SessionDto } from '../types/api';

/** Get the session for the alias carried by the current URL. */
export async function getSession(signal?: AbortSignal): Promise<SessionDto> {
  return get<SessionDto>('/session', undefined, signal);
}
