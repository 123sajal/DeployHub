import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';

import { ApiError } from '../models/api.models';

/**
 * Turns every failed API call into one predictable ApiError shape, so components
 * never have to dig through HttpErrorResponse themselves.
 */
export const apiErrorInterceptor: HttpInterceptorFn = (req, next) =>
  next(req).pipe(catchError((error: unknown) => throwError(() => toApiError(error))));

export function toApiError(error: unknown): ApiError {
  if (!(error instanceof HttpErrorResponse)) {
    return { status: 0, title: 'Unexpected error', detail: String(error), fieldErrors: {} };
  }
  if (error.status === 0) {
    return {
      status: 0,
      title: 'API unreachable',
      detail: 'Cannot reach the DeployHub API. Is the backend running on port 8080?',
      fieldErrors: {},
    };
  }
  const body = (typeof error.error === 'object' && error.error) || {};
  return {
    status: error.status,
    title: body.title ?? error.statusText ?? 'Request failed',
    detail: body.detail ?? 'The request failed.',
    fieldErrors: body.errors ?? {},
  };
}
