import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthService } from '../services/auth/auth.service';

/**
 * If any API call (other than the auth endpoints themselves) answers 401, the session has
 * expired or was ended elsewhere: forget the user and go to the login page, remembering where
 * they were.
 */
export const sessionExpiredInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return next(req).pipe(
    catchError((error: { status?: number }) => {
      if (error?.status === 401 && !req.url.includes('/auth/') && auth.isAuthenticated()) {
        auth.clearSession();
        router.navigate(['/login'], { queryParams: { reason: 'expired', returnUrl: router.url } });
      }
      return throwError(() => error);
    }),
  );
};
