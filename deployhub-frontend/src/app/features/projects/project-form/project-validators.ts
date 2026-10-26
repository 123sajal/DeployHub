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

export const PROJECT_NAME_PATTERN = /^[a-z0-9][a-z0-9-]*[a-z0-9]$/;
export const BRANCH_PATTERN = /^(?!.*\.\.)[A-Za-z0-9._/-]+$/;
