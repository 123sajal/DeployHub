import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { ProjectApiService } from './project-api.service';

describe('ProjectApiService', () => {
  let service: ProjectApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ProjectApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('asks the server to detect the type and commands from the repository', () => {
    service.detect('https://github.com/acme/ems', 'main', 'backend').subscribe();
    const req = http.expectOne('/api/projects/detect');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      repositoryUrl: 'https://github.com/acme/ems', branch: 'main', rootDirectory: 'backend', repositoryToken: null, projectId: null,
    });
    req.flush({ appType: null, buildCommand: null, startCommand: null, appFolders: [], notes: [] });
  });
});
