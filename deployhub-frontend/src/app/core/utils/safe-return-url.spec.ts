import { safeReturnUrl } from './safe-return-url';

describe('safeReturnUrl', () => {
  it('keeps in-app paths', () => {
    expect(safeReturnUrl('/app/projects/3?tab=logs')).toBe('/app/projects/3?tab=logs');
  });

  it.each(['https://evil.example', '//evil.example', '/\\evil.example', 'javascript:alert(1)', '', null, '/login'])(
    'falls back for %s',
    (url) => {
      expect(safeReturnUrl(url)).toBe('/app/dashboard');
    },
  );
});
