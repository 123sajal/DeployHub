import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

const ALLOWED_HOSTS = ['github.com', 'gitlab.com', 'bitbucket.org'];

/**
 * Mirrors the backend's RepositoryUrlValidator so users get instant feedback.
 * The backend check is the one that matters for security; this is only for UX.
 */
export const repositoryUrlValidator: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value = (control.value ?? '').trim();
  if (!value) return null;
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return { repositoryUrl: 'Enter a full URL, e.g. https://github.com/owner/repo' };
  }
  if (url.protocol !== 'https:') return { repositoryUrl: 'Only https:// URLs are allowed' };
  if (url.username || url.password) return { repositoryUrl: 'Do not put credentials in the URL' };
  if (url.port) return { repositoryUrl: 'Custom ports are not allowed' };
  if (!ALLOWED_HOSTS.includes(url.hostname.toLowerCase())) {
    return { repositoryUrl: `Host must be one of: ${ALLOWED_HOSTS.join(', ')}` };
  }
  if (url.search || url.hash) return { repositoryUrl: 'Remove query strings and fragments' };
  if (!/^(\/[A-Za-z0-9_.-]+){2,}\/?$/.test(url.pathname) || url.pathname.includes('..')) {
    return { repositoryUrl: 'URL must point to a repository: https://host/owner/repo' };
  }
  return null;
};

/**
 * Validators.pattern / minLength / maxLength, but on the value without spaces at the start and end: a space typed
 * after "my-api" is not a mistake (the form and the server both trim it).
 */
export function trimmedPattern(pattern: RegExp): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = String(control.value ?? '').trim();
    return !value || pattern.test(value) ? null : { pattern: { requiredPattern: String(pattern), actualValue: value } };
  };
}

export function trimmedLength(min: number, max: number): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const raw = String(control.value ?? '');
    const length = raw.trim().length;
    if (min > 0 && length === 0 && raw.length > 0) return { required: true }; // only spaces
    if (length > 0 && length < min) return { minlength: { requiredLength: min, actualLength: length } };
    if (length > max) return { maxlength: { requiredLength: max, actualLength: length } };
    return null;
  };
}

export const PROJECT_NAME_PATTERN = /^[a-z0-9][a-z0-9-]*[a-z0-9]$/;
export const BRANCH_PATTERN = /^(?!.*\.\.)[A-Za-z0-9._/-]+$/;
/** Mirrors the backend: a folder inside the repository, e.g. backend or apps/web (no "..", no leading "/"). */
export const APP_FOLDER_PATTERN = /^\s*(\.\/)?[A-Za-z0-9_-][A-Za-z0-9._-]*(\/[A-Za-z0-9_-][A-Za-z0-9._-]*)*\/?\s*$|^\s*$/;

/** "./backend/" -> "backend"; empty -> null (the repository root). Same tidying as the backend. */
export function normaliseAppFolder(value: string): string | null {
  let folder = value.trim();
  if (folder.startsWith('./')) folder = folder.slice(2);
  if (folder.endsWith('/')) folder = folder.slice(0, -1);
  return folder || null;
}
