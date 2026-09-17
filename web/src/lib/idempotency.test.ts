import { beforeEach, describe, expect, it } from 'vitest';
import { canonical, fingerprint, finish, keyFor, pending } from './idempotency';

describe('idempotency keys', () => {
  beforeEach(() => window.sessionStorage.clear());
  it('is stable for the same payload regardless of key order and undefined fields', () => {
    expect(canonical({ b: 1, a: [1, { d: 2, c: 3 }], u: undefined })).toBe(
      '{"a":[1,{"c":3,"d":2}],"b":1}',
    );
    expect(fingerprint({ a: 1, b: 2 })).toBe(fingerprint({ b: 2, a: 1 }));
    expect(fingerprint({ a: 1 })).not.toBe(fingerprint({ a: 2 }));
  });
  it('mints one key per operation and payload until finished', () => {
    const k1 = keyFor('create-trip', { origin: 'BOS' });
    const k2 = keyFor('create-trip', { origin: 'BOS' });
    expect(k2).toBe(k1);
    expect(keyFor('create-trip', { origin: 'SEA' })).not.toBe(k1);
    expect(pending('create-trip')).toHaveLength(2);
    finish('create-trip', { origin: 'BOS' });
    expect(pending('create-trip')).toHaveLength(1);
    expect(keyFor('create-trip', { origin: 'BOS' })).not.toBe(k1);
  });
});
