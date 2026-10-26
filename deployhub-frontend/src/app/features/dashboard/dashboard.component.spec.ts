import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { DashboardSummary, DeploymentMetrics } from '../../core/models/api.models';
import { PlatformApiService } from '../../core/services/api/platform-api.service';
import { DashboardComponent } from './dashboard.component';

const SUMMARY: DashboardSummary = {
  totalProjects: 3,
  queued: 1,
  running: 2,
  successful: 5,
  failed: 1,
  cancelled: 0,
  recentDeployments: [],
};

const METRICS: DeploymentMetrics = {
  windowMinutes: 60,
  platformQueue: { waiting: 4, retryScheduled: 1, running: 2 },
  avgQueueWaitMs: 4200,
  maxQueueWaitMs: 95000,
  finished: 6,
  succeeded: 4,
  failed: 1,
  cancelled: 1,
  successRate: 0.8,
};

describe('DashboardComponent', () => {
  // The component polls with rxjs timer(); fake timers let the test step through it.
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  let fixture: ComponentFixture<DashboardComponent>;

  async function render(dashboardSummary: () => unknown) {
    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        { provide: PlatformApiService, useValue: { dashboardSummary, deploymentMetrics: () => of(METRICS) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows a loading state until data arrives', async () => {
    const pending = new Subject<DashboardSummary>();
    const el = await render(() => pending);
    expect(el.querySelector('[aria-busy="true"]')).not.toBeNull();
  });

  it('renders statistics and the empty activity message', async () => {
    const el = await render(() => of(SUMMARY));
    const values = Array.from(el.querySelectorAll('.stat .value')).map((v) => v.textContent?.trim());
    expect(values).toEqual(['3', '1', '2', '5', '1']);
    expect(el.textContent).toContain('No deployments yet');
  });

  it('shows the last hour of pipeline metrics', async () => {
    const el = await render(() => of(SUMMARY));
    const metrics = el.querySelector('.metrics')?.textContent?.replace(/\s+/g, ' ') ?? '';
    expect(metrics).toContain('Last 60 minutes');
    expect(metrics).toContain('4 waiting');
    expect(metrics).toContain('2 running · 1 retrying');
    expect(metrics).toContain('4.2 s');
    expect(metrics).toContain('max 1 min 35 s');
    expect(metrics).toContain('80%');
  });

  it('shows the error and a retry button when the API fails', async () => {
    const el = await render(() =>
      throwError(() => ({ status: 0, title: 'API unreachable', detail: 'Cannot reach', fieldErrors: {} })),
    );
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('API unreachable');
    expect(el.textContent).toContain('Try again');
  });

  it('keeps polling through an outage and recovers on its own', async () => {
    const dashboardSummary = vi
      .fn()
      .mockReturnValueOnce(throwError(() => ({ status: 0, title: 'API unreachable', detail: '', fieldErrors: {} })))
      .mockReturnValue(of(SUMMARY));
    const el = await render(dashboardSummary);
    expect(el.querySelector('[role="alert"]')).not.toBeNull();

    await vi.advanceTimersByTimeAsync(5100); // retried after the refresh interval
    fixture.detectChanges();

    expect(dashboardSummary).toHaveBeenCalledTimes(2);
    expect(el.querySelector('[role="alert"]')).toBeNull();
    expect(Array.from(el.querySelectorAll('.stat .value')).map((v) => v.textContent?.trim())).toEqual(['3', '1', '2', '5', '1']);
  });

  it('stops polling on an error that retrying cannot fix', async () => {
    const dashboardSummary = vi.fn(() => throwError(() => ({ status: 403, title: 'Forbidden', detail: '', fieldErrors: {} })));
    const el = await render(dashboardSummary);
    await vi.advanceTimersByTimeAsync(20000);

    expect(dashboardSummary).toHaveBeenCalledTimes(1);
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Forbidden');
  });
});
