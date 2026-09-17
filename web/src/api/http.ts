import { ApiError, UncertainError, type Problem } from '@/lib/problem';

type TokenProvider = () => string | null;
let tokenProvider: TokenProvider = () => null;
let onUnauthorized: () => void = () => {};

export function installAuth(provider: TokenProvider, unauthorized: () => void): void {
  tokenProvider = provider;
  onUnauthorized = unauthorized;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  /** Required for every mutation; minted once per operation (lib/idempotency). */
  idempotencyKey?: string;
  signal?: AbortSignal;
  timeoutMs?: number;
}

/**
 * The one HTTP door. Same-origin, bearer token from memory, JSON both ways, problem details into
 * ApiError, network failures during a mutation into UncertainError (the caller reconciles).
 */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, idempotencyKey, signal, timeoutMs = 30_000 } = options;
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = tokenProvider();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  if (method !== 'GET' && !idempotencyKey && !path.includes('/config')) {
    throw new Error(`mutation without an idempotency key: ${method} ${path}`);
  }
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(new Error('timeout')), timeoutMs);
  signal?.addEventListener('abort', () => controller.abort(signal.reason));
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      credentials: 'omit',
    });
  } catch (e) {
    if (method === 'GET') throw new UncertainError('the service could not be reached', e);
    throw new UncertainError(
      'the request may not have reached the service; check its state before trying again',
      e,
    );
  } finally {
    window.clearTimeout(timer);
  }
  if (response.status === 401) {
    onUnauthorized();
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!response.ok) {
    throw new ApiError(problemFrom(response.status, text));
  }
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

function problemFrom(status: number, text: string): Problem {
  try {
    const p = JSON.parse(text) as Partial<Problem> & { fields?: Record<string, string> };
    return {
      status: p.status ?? status,
      code: p.code ?? p.title ?? `HTTP_${status}`,
      title: p.title ?? `HTTP ${status}`,
      detail: p.detail ?? '',
      ...(p.fields ? { fields: p.fields } : {}),
    };
  } catch {
    return { status, code: `HTTP_${status}`, title: `HTTP ${status}`, detail: text.slice(0, 300) };
  }
}

export function query(params: Record<string, string | number | undefined | null>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') q.set(k, String(v));
  }
  const s = q.toString();
  return s ? `?${s}` : '';
}
