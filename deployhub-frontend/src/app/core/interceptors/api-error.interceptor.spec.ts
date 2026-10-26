import { HttpErrorResponse } from '@angular/common/http';

import { toApiError } from './api-error.interceptor';

describe('toApiError', () => {
  it('reads an RFC 9457 problem body including field errors', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: { title: 'Validation failed', detail: 'One or more fields are invalid.', errors: { name: 'bad' } },
    });
    expect(toApiError(error)).toEqual({
      status: 400,
      title: 'Validation failed',
      detail: 'One or more fields are invalid.',
      fieldErrors: { name: 'bad' },
    });
  });

  it('explains a network failure instead of showing status 0', () => {
    const result = toApiError(new HttpErrorResponse({ status: 0 }));
    expect(result.title).toBe('API unreachable');
    expect(result.detail).toContain('8080');
  });

  it('copes with a non-JSON error body', () => {
    const result = toApiError(new HttpErrorResponse({ status: 502, statusText: 'Bad Gateway', error: '<html>' }));
    expect(result.status).toBe(502);
    expect(result.fieldErrors).toEqual({});
  });
});
