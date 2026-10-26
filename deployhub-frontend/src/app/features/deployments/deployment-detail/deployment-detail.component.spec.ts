import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { of } from 'rxjs';

import { Deployment, DeploymentHistory } from '../../../core/models/api.models';
import { DeploymentApiService } from '../../../core/services/api/deployment-api.service';
import { DeploymentDetailComponent } from './deployment-detail.component';

const FAILED: Deployment = {
  id: 9,
  projectId: 1,
  projectName: 'api',
  status: 'FAILED',
  executionMode: 'SIMULATED',
  commitRef: null,
  failureReason: 'Gave up after 3 attempts: Simulated transient error: package registry timed out',
  cancellable: false,
  rollbackable: false,
  rollbackOfId: null,
  createdAt: '2026-10-25T10:00:00Z',
  statusChangedAt: '2026-10-25T10:00:09Z',
  startedAt: '2026-10-25T10:00:01Z',
  finishedAt: '2026-10-25T10:00:09Z',
};

const HISTORY: DeploymentHistory = {
  job: { status: 'DEAD', attempt: 3, maxAttempts: 3, nextAttemptAt: null, lastError: 'package registry timed out', workerId: null },
  transitions: [
    { fromStatus: null, toStatus: 'QUEUED', reason: 'Deployment requested', actor: 'user:1', attempt: null, occurredAt: '2026-10-25T10:00:00Z' },
    { fromStatus: 'QUEUED', toStatus: 'BUILDING', reason: null, actor: 'worker:mac-1a2b', attempt: 1, occurredAt: '2026-10-25T10:00:01Z' },
    { fromStatus: 'BUILDING', toStatus: 'FAILED', reason: 'Gave up after 3 attempts', actor: 'worker:mac-1a2b', attempt: 3, occurredAt: '2026-10-25T10:00:09Z' },
  ],
};

const REPLACED: Deployment = {
  ...FAILED,
  id: 7,
  status: 'SUPERSEDED',
  commitRef: 'v1.4.0',
  failureReason: null,
  rollbackable: true,
};

describe('DeploymentDetailComponent', () => {
  // The page polls with rxjs timer(); fake timers let the test step through it.
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  async function render(deployment: Deployment = FAILED, extra: Record<string, unknown> = {}) {
    await TestBed.configureTestingModule({
      imports: [DeploymentDetailComponent],
      providers: [
        provideRouter([]),
        MessageService,
        ConfirmationService,
        {
          provide: DeploymentApiService,
          useValue: {
            get: () => of(deployment),
            history: () => of(HISTORY),
            logs: () => of({ items: [], nextCursor: 0, hasMore: false, deploymentStatus: 'FAILED' }),
            logStreamUrl: () => '/api/stream',
            ...extra,
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(DeploymentDetailComponent);
    fixture.componentRef.setInput('id', String(deployment.id));
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('explains that retries were exhausted', async () => {
    const el = await render();
    expect(el.textContent).toContain('Gave up after 3 attempts');
    expect(el.textContent).toContain('Last error: package registry timed out');
  });

  it('shows who changed the status and on which attempt', async () => {
    const el = await render();
    const rows = Array.from(el.querySelectorAll('.history li')).map((li) => li.textContent?.replace(/\s+/g, ' '));
    expect(rows).toHaveLength(3);
    expect(rows[0]).toContain('You');
    expect(rows[2]).toContain('Worker mac-1a2b');
    expect(rows[2]).toContain('attempt 3');
  });

  it('offers no rollback for a deployment that never went live', async () => {
    const el = await render();
    expect(el.textContent).not.toContain('Roll back to this');
  });

  it('rolls back to a replaced deployment and opens the new one', async () => {
    const rollback = vi.fn(() => of({ ...REPLACED, id: 12, status: 'QUEUED', rollbackable: false, rollbackOfId: 7 }));
    const el = await render(REPLACED, { rollback });
    vi.spyOn(TestBed.inject(ConfirmationService), 'confirm').mockImplementation((c) => {
      c.accept?.();
      return TestBed.inject(ConfirmationService);
    });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    const button = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Roll back to this'));
    button!.click();

    expect(rollback).toHaveBeenCalledWith(7, expect.stringMatching(/^[A-Za-z0-9._:-]{1,100}$/));
    expect(navigate).toHaveBeenCalledWith(['/app/deployments', 12]);
  });

  it('marks the step where the deployment stopped', async () => {
    const el = await render();
    const failedStep = el.querySelector('.pipeline li.failed');
    expect(failedStep?.textContent).toContain('Building');
  });
});
