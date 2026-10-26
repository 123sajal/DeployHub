import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AuthService } from './auth.service';

const ALICE = { id: 1, email: 'alice@example.com', displayName: 'Alice' };

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('restores the logged-in user from the session after a page refresh', () => {
    auth.restore().subscribe();
    http.expectOne('/api/auth/me').flush(ALICE);
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.user()?.email).toBe('alice@example.com');
  });

  it('starts logged out when the session is missing or expired', () => {
    auth.restore().subscribe();
    http.expectOne('/api/auth/me').flush({ title: 'Authentication required' }, { status: 401, statusText: 'Unauthorized' });
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('logs in and keeps nothing in browser storage', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    auth.login({ email: 'alice@example.com', password: 'correct-horse-battery' }).subscribe();
    const req = http.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    req.flush(ALICE);

    expect(auth.user()).toEqual(ALICE);
    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });

  it('clears the user on logout even if the request fails', () => {
    auth.login({ email: 'a@b.co', password: 'x' }).subscribe();
    http.expectOne('/api/auth/login').flush(ALICE);

    auth.logout().subscribe();
    http.expectOne('/api/auth/logout').flush(null, { status: 503, statusText: 'Unavailable' });
    expect(auth.isAuthenticated()).toBe(false);
  });
});
