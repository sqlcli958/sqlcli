import { beforeEach, expect, test } from 'vitest';
import { useSessionStore } from './sessionStore';

beforeEach(() => {
  sessionStorage.clear();
  useSessionStore.getState().clearSession();
});

test('a tokenless session clears a stale session token', () => {
  sessionStorage.setItem('sql-cli-session-token', 'stale-token');

  useSessionStore.getState().setSession('demo', null, 3, true, ['read']);

  expect(sessionStorage.getItem('sql-cli-session-token')).toBeNull();
  expect(useSessionStore.getState().token).toBeNull();
});
