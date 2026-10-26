import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from '../services/auth/auth.service';

/**
 * Keeps logged-out visitors out of /app. This is for user experience only: the real security
 * boundary is the backend, which rejects every unauthenticated or foreign-owned request.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  if (inject(AuthService).isAuthenticated()) {
    return true;
  }
  return inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

/** Sends already-logged-in users from /login and /signup straight to the dashboard. */
export const guestGuard: CanActivateFn = () =>
  inject(AuthService).isAuthenticated() ? inject(Router).createUrlTree(['/app/dashboard']) : true;
