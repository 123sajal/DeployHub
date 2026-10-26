import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { DeploymentApiService } from './deployment-api.service';

describe('DeploymentApiService', () => {
  let service: DeploymentApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(DeploymentApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests logs after a cursor', () => {
    service.logs(7, 42, 100).subscribe();
    const req = http.expectOne((r) => r.url === '/api/deployments/7/logs');
    expect(req.request.params.get('after')).toBe('42');
    expect(req.request.params.get('limit')).toBe('100');
    req.flush({ items: [], nextCursor: 42, hasMore: false, deploymentStatus: 'LIVE' });
  });

  it('queues a deployment with an idempotency key and an empty body when no commit is given', () => {
    service.create(3, 'click-1').subscribe();
    const req = http.expectOne('/api/projects/3/deployments');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toBe('click-1');
    expect(req.request.body).toBeNull();
    req.flush({});
  });

  it('loads the status history', () => {
    service.history(5).subscribe();
    http.expectOne('/api/deployments/5/history').flush({ job: null, transitions: [] });
  });

  it('only sends filters that are set', () => {
    service.list({ status: 'FAILED' }).subscribe();
    const req = http.expectOne((r) => r.url === '/api/deployments');
    expect(req.request.params.get('status')).toBe('FAILED');
    expect(req.request.params.has('projectId')).toBe(false);
    req.flush({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  });
});
