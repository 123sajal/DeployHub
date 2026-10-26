/**
 * Only allow redirects back into this app after login. Rejects absolute and protocol-relative
 * URLs ("https://evil.example", "//evil.example") to prevent open redirects.
 */
export function safeReturnUrl(url: string | null | undefined, fallback = '/app/dashboard'): string {
  if (!url || !url.startsWith('/') || url.startsWith('//') || url.startsWith('/\\')) {
    return fallback;
  }
  if (url.startsWith('/login') || url.startsWith('/signup')) {
    return fallback;
  }
  return url;
}
