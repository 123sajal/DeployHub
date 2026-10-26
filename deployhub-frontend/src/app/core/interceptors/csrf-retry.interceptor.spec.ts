import { HttpClient, HttpXsrfTokenExtractor, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { csrfRetryInterceptor } from './csrf-retry.interceptor';

describe('csrfRetryInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let token: string | null;

  beforeEach(() => {
    token = null;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([csrfRetryInterceptor])),
        provideHttpClientTesting(),
        { provide: HttpXsrfTokenExtractor, useValue: { getToken: () => token } },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('fetches a fresh token and retries a write that was blocked (logout → login without reload)', () => {
    let result: unknown;
    http.post('/api/auth/login', { email: 'a@b.co' }).subscribe((r) => (result = r));

    backend.expectOne('/api/auth/login').flush({ title: 'Forbidden' }, { status: 403, statusText: 'Forbidden' });
    token = 'fresh-token';
    backend.expectOne('/api/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });

    const retry = backend.expectOne('/api/auth/login');
    expect(retry.request.headers.get('X-XSRF-TOKEN')).toBe('fresh-token');
    retry.flush({ id: 1 });
    expect(result).toEqual({ id: 1 });
  });

  it('retries only once, so a real 403 still reaches the caller', () => {
    let failed = false;
    http.post('/api/projects', {}).subscribe({ error: () => (failed = true) });

    backend.expectOne('/api/projects').flush(null, { status: 403, statusText: 'Forbidden' });
    backend.expectOne('/api/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });
    backend.expectOne('/api/projects').flush(null, { status: 403, statusText: 'Forbidden' });

    expect(failed).toBe(true);
  });

  it('leaves reads and other errors alone', () => {
    http.get('/api/projects').subscribe({ error: () => undefined });
    backend.expectOne('/api/projects').flush(null, { status: 403, statusText: 'Forbidden' });

    http.post('/api/projects', {}).subscribe({ error: () => undefined });
    backend.expectOne('/api/projects').flush(null, { status: 500, statusText: 'Error' });
    backend.expectNone('/api/auth/csrf');
  });
});
