/**
 * A random Idempotency-Key. crypto.randomUUID() only exists on secure pages (https or localhost),
 * so on plain http (e.g. a server reached by IP before HTTPS is set up) fall back to
 * crypto.getRandomValues(), which works everywhere. Both give 122+ random bits.
 */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}
