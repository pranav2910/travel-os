import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch, installAuth } from './http';
import { ApiError, UncertainError } from '@/lib/problem';

describe('apiFetch', () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    installAuth(
      () => 'tok',
      () => {},
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  it('sends the bearer token and the idempotency key', async () => {
    fetchMock.mockResolvedValue(new Response('{"ok":true}', { status: 200 }));
    await apiFetch('/api/v1/trips', { method: 'POST', body: { a: 1 }, idempotencyKey: 'k1' });
    const init = fetchMock.mock.calls[0]![1] as RequestInit;
    const headers = init.headers as Record<string, string>;
    expect(headers['Authorization']).toBe('Bearer tok');
    expect(headers['Idempotency-Key']).toBe('k1');
    expect(init.credentials).toBe('omit');
  });
  it('refuses a mutation without a key before any request leaves', async () => {
    await expect(apiFetch('/api/v1/trips', { method: 'POST', body: {} })).rejects.toThrow(
      /idempotency key/,
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });
  it('turns problem details into ApiError and calls the 401 handler', async () => {
    const unauthorized = vi.fn();
    installAuth(() => 'tok', unauthorized);
    fetchMock.mockResolvedValue(
      new Response(
        '{"status":401,"code":"UNAUTHENTICATED","title":"Unauthorized","detail":"expired"}',
        { status: 401 },
      ),
    );
    await expect(apiFetch('/api/v1/trips')).rejects.toBeInstanceOf(ApiError);
    expect(unauthorized).toHaveBeenCalledOnce();
    fetchMock.mockResolvedValue(
      new Response('{"status":409,"code":"VERSION_CONFLICT","title":"Conflict","detail":"stale"}', {
        status: 409,
      }),
    );
    const err = await apiFetch('/api/v1/learning/config', {
      method: 'PUT',
      body: {},
      idempotencyKey: 'k',
    }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe('VERSION_CONFLICT');
    expect((err as ApiError).is(409)).toBe(true);
  });
  it('marks a failed mutation transport as uncertain, never as success', async () => {
    fetchMock.mockRejectedValue(new TypeError('network down'));
    const err = await apiFetch('/api/v1/trips', {
      method: 'POST',
      body: {},
      idempotencyKey: 'k',
    }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(UncertainError);
  });
});
