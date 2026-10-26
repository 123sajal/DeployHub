import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';

import { AuthService } from '../services/auth/auth.service';
import { authGuard, guestGuard } from './auth.guard';

describe('auth guards', () => {
  let loggedIn: boolean;

  beforeEach(() => {
    loggedIn = false;
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: { isAuthenticated: () => loggedIn } }],
    });
  });

  const run = (guard: typeof authGuard, url = '/app/projects/7') =>
    TestBed.runInInjectionContext(() =>
      guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    );

  it('sends logged-out visitors to /login and remembers where they were going', () => {
    const result = run(authGuard) as UrlTree;
    expect(TestBed.inject(Router).serializeUrl(result)).toBe('/login?returnUrl=%2Fapp%2Fprojects%2F7');
  });

  it('lets logged-in users into the app', () => {
    loggedIn = true;
    expect(run(authGuard)).toBe(true);
  });

  it('redirects logged-in users away from login and sign-up', () => {
    loggedIn = true;
    expect(TestBed.inject(Router).serializeUrl(run(guestGuard) as UrlTree)).toBe('/app/dashboard');
    loggedIn = false;
    expect(run(guestGuard)).toBe(true);
  });
});
