import { FormControl } from '@angular/forms';

import {
  APP_FOLDER_PATTERN,
  BRANCH_PATTERN,
  PROJECT_NAME_PATTERN,
  normaliseAppFolder,
  repositoryUrlValidator,
} from './project-validators';

const check = (url: string) => repositoryUrlValidator(new FormControl(url));

describe('repositoryUrlValidator', () => {
  it.each([
    'https://github.com/spring-projects/spring-petclinic',
    'https://gitlab.com/group/subgroup/repo.git',
    'https://bitbucket.org/team/repo',
  ])('accepts %s', (url) => {
    expect(check(url)).toBeNull();
  });

  it.each([
    ['http://github.com/a/b', 'https'],
    ['https://169.254.169.254/latest/meta-data', 'Host'],
    ['https://user:pw@github.com/a/b', 'credentials'],
    ['https://github.com:8443/a/b', 'ports'],
    ['https://github.com/owner', 'repository'],
    ['https://github.com/a/b?x=1', 'query'],
    ['not a url', 'full URL'],
  ])('rejects %s', (url, hint) => {
    expect(check(url)?.['repositoryUrl']).toContain(hint);
  });

  it('leaves empty values to the required validator', () => {
    expect(check('')).toBeNull();
  });
});

describe('name and branch patterns', () => {
  it('match the backend rules', () => {
    expect(PROJECT_NAME_PATTERN.test('my-api')).toBe(true);
    expect(PROJECT_NAME_PATTERN.test('-bad')).toBe(false);
    expect(PROJECT_NAME_PATTERN.test('Bad')).toBe(false);
    expect(BRANCH_PATTERN.test('feature/login-v2')).toBe(true);
    expect(BRANCH_PATTERN.test('a..b')).toBe(false);
  });
});

describe('app folder', () => {
  it('accepts folders inside the repository and rejects anything that leaves it', () => {
    for (const ok of ['backend', 'apps/web', './frontend/', 'my_app-2', '']) expect(APP_FOLDER_PATTERN.test(ok)).toBe(true);
    for (const bad of ['../secrets', '/etc', 'backend/../../x', 'a b', 'back;rm', '.git', '~/x']) {
      expect(APP_FOLDER_PATTERN.test(bad)).toBe(false);
    }
  });

  it('is tidied the same way as on the server', () => {
    expect(normaliseAppFolder(' ./backend/ ')).toBe('backend');
    expect(normaliseAppFolder('apps/web')).toBe('apps/web');
    expect(normaliseAppFolder('  ')).toBeNull();
  });
});
