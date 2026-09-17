/**
 * One idempotency key per intended operation. The key is minted when the operation is first
 * attempted and kept (in sessionStorage, per tab) until the operation is known to have completed,
 * so a retry of the same payload after a timeout, a double click or a reload sends the same key
 * and the backend replays the same result. A changed payload is a different operation and gets a
 * new key; the backend refuses a reused key with a different body (IDEMPOTENCY_KEY_REUSED).
 */
const PREFIX = 'travelos.op.';

export function canonical(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  const obj = value as Record<string, unknown>;
  const keys = Object.keys(obj)
    .filter((k) => obj[k] !== undefined)
    .sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${canonical(obj[k])}`).join(',')}}`;
}

export function fingerprint(value: unknown): string {
  // FNV-1a over the canonical JSON: a stable, tiny namespace for the per-tab key table
  let h = 0x811c9dc5;
  const s = canonical(value);
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(16).padStart(8, '0');
}

export function newKey(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

function storage(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

/** The key for (operation, payload): the same one as long as the operation is unfinished. */
export function keyFor(operation: string, payload: unknown): string {
  const slot = `${PREFIX}${operation}.${fingerprint(payload)}`;
  const store = storage();
  const existing = store?.getItem(slot);
  if (existing) return existing;
  const key = newKey();
  store?.setItem(slot, key);
  return key;
}

export function finish(operation: string, payload: unknown): void {
  storage()?.removeItem(`${PREFIX}${operation}.${fingerprint(payload)}`);
}

/** Operations that were started in this tab and never confirmed (a reload mid-flight). */
export function pending(operation: string): string[] {
  const store = storage();
  if (!store) return [];
  const out: string[] = [];
  for (let i = 0; i < store.length; i++) {
    const k = store.key(i);
    if (k?.startsWith(`${PREFIX}${operation}.`)) out.push(k);
  }
  return out;
}

export function forgetAll(): void {
  const store = storage();
  if (!store) return;
  for (const k of pending('')) store.removeItem(k);
}
