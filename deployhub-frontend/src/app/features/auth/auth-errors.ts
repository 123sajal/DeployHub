import { ApiError } from '../../core/models/api.models';

/** Human-friendly text for login/sign-up failures that aren't tied to a single field. */
export function authErrorMessage(err: ApiError): string {
  switch (err.status) {
    case 401:
      return 'Invalid email or password.';
    case 429:
      return err.detail || 'Too many attempts. Please wait a few minutes and try again.';
    case 503:
      return 'DeployHub is temporarily unavailable. Please try again shortly.';
    case 403:
      return 'Your request was blocked. Reload the page and try again.';
    default:
      return err.detail || 'Something went wrong. Please try again.';
  }
}
