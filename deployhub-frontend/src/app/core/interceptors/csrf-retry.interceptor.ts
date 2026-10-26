import {
  HttpErrorResponse,
  HttpEventType,
  HttpInterceptorFn,
  HttpRequest,
  HttpXsrfTokenExtractor,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, filter, switchMap, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
const CSRF_URL = `${environment.apiUrl}/auth/csrf`;

/**
 * Safety net for the CSRF token. If a write is rejected with 403 (typically because the browser has
 * no XSRF-TOKEN cookie, or an outdated one), fetch a fresh token and retry that request exactly once,
 * so the user never sees "request blocked" for something the app can fix itself.
 */
export const csrfRetryInterceptor: HttpInterceptorFn = (req, next) => {
  if (!WRITE_METHODS.has(req.method) || !req.url.startsWith(environment.apiUrl)) {
    return next(req);
  }
  const tokens = inject(HttpXsrfTokenExtractor);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 403) {
        return throwError(() => error);
      }
      return next(new HttpRequest('GET', CSRF_URL)).pipe(
        filter((event) => event.type === HttpEventType.Response),
        switchMap(() => {
          const token = tokens.getToken();
          return next(token ? req.clone({ setHeaders: { 'X-XSRF-TOKEN': token } }) : req);
        }),
      );
    }),
  );
};
