import { newIdempotencyKey } from './idempotency-key';

// The backend accepts 1-100 of: letters, digits, '.', '_', ':' or '-'.
const BACKEND_KEY = /^[A-Za-z0-9._:-]{1,100}$/;

describe('newIdempotencyKey', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('uses crypto.randomUUID when the page is secure', () => {
    const key = newIdempotencyKey();
    expect(key).toMatch(BACKEND_KEY);
    expect(newIdempotencyKey()).not.toBe(key);
  });

  it('falls back to getRandomValues on plain http, where randomUUID does not exist', () => {
    const real = crypto;
    vi.stubGlobal('crypto', { getRandomValues: (a: Uint8Array) => real.getRandomValues(a) });
    const key = newIdempotencyKey();
    expect(key).toMatch(/^[0-9a-f]{32}$/);
    expect(key).toMatch(BACKEND_KEY);
    expect(newIdempotencyKey()).not.toBe(key);
  });
});
