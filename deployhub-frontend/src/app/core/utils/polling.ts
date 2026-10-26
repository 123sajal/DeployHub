import { MonoTypeOperatorFunction, retry, throwError, timer } from 'rxjs';

import { ApiError } from '../models/api.models';

/**
 * Worth retrying: the API is unreachable (0), overloaded (429) or failing (5xx).
 * Anything else (404 deleted, 401 logged out, 403...) won't fix itself by asking again.
 */
export function isTransient(err: ApiError): boolean {
  return err.status === 0 || err.status === 429 || err.status >= 500;
}

/**
 * Keeps a polling stream alive through transient failures: reports the error, waits `delayMs`,
 * then resubscribes (so polling resumes on its own once the backend is back). Other errors end it.
 * Place it after the request and before takeWhile, so a resubscribe restarts the timer.
 */
export function retryWhileTransient<T>(
  delayMs: number,
  onError: (err: ApiError) => void,
): MonoTypeOperatorFunction<T> {
  return retry({
    delay: (err: ApiError) => {
      onError(err);
      return isTransient(err) ? timer(delayMs) : throwError(() => err);
    },
  });
}
