import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AuthService } from '../services/auth/auth.service';
import { sessionExpiredInterceptor } from './session-expired.interceptor';

describe('sessionExpiredInterceptor', () => {
  const auth = { isAuthenticated: vi.fn(() => true), clearSession: vi.fn() };
  let http: HttpClient;
  let backend: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    auth.clearSession.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([sessionExpiredInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  it('logs the user out locally and goes to /login when an API call returns 401', () => {
    http.get('/api/projects').subscribe({ error: () => undefined });
    backend.expectOne('/api/projects').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.clearSession).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/login'], expect.objectContaining({ queryParams: expect.objectContaining({ reason: 'expired' }) }));
  });

  it('leaves 401s from the auth endpoints to the login form', () => {
    http.post('/api/auth/login', {}).subscribe({ error: () => undefined });
    backend.expectOne('/api/auth/login').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(navigate).not.toHaveBeenCalled();
  });

  it('ignores other errors', () => {
    http.get('/api/projects').subscribe({ error: () => undefined });
    backend.expectOne('/api/projects').flush(null, { status: 500, statusText: 'Error' });
    expect(auth.clearSession).not.toHaveBeenCalled();
  });
});
