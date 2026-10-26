import { FormControl } from '@angular/forms';

import { BRANCH_PATTERN, PROJECT_NAME_PATTERN, repositoryUrlValidator } from './project-validators';

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
