import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { Subscription, forkJoin, switchMap, timer } from 'rxjs';

import { PageStateComponent } from '../../core/components/shared/page-state.component';
import { SimulatedBadgeComponent } from '../../core/components/shared/simulated-badge.component';
import { StatusBadgeComponent } from '../../core/components/shared/status-badge.component';
import { ApiError, DashboardSummary, DeploymentMetrics } from '../../core/models/api.models';
import { PlatformApiService } from '../../core/services/api/platform-api.service';
import { retryWhileTransient } from '../../core/utils/polling';

const REFRESH_MS = 5000;

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, DatePipe, ButtonModule, PageStateComponent, StatusBadgeComponent, SimulatedBadgeComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit {
  private readonly platformApi = inject(PlatformApiService);
  private readonly destroyRef = inject(DestroyRef);
  private refresh?: Subscription;

  protected readonly summary = signal<DashboardSummary | null>(null);
  protected readonly metrics = signal<DeploymentMetrics | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);

  protected readonly stats = computed(() => {
    const s = this.summary();
    if (!s) return [];
    return [
      { label: 'Projects', value: s.totalProjects, icon: 'pi-folder', tone: 'primary' },
      { label: 'Queued', value: s.queued, icon: 'pi-clock', tone: 'muted' },
      { label: 'Running', value: s.running, icon: 'pi-spinner', tone: 'info' },
      { label: 'Successful', value: s.successful, icon: 'pi-check-circle', tone: 'success' },
      { label: 'Failed', value: s.failed, icon: 'pi-times-circle', tone: 'danger' },
    ];
  });

  ngOnInit(): void {
    this.load();
  }

  /**
   * Re-polls every few seconds so simulated deployments visibly progress. If the API goes away
   * the error is shown, polling carries on, and the page recovers by itself when it's back.
   */
  protected load(): void {
    this.refresh?.unsubscribe();
    this.loading.set(this.summary() === null);
    this.error.set(null);
    this.refresh = timer(0, REFRESH_MS)
      .pipe(
        switchMap(() =>
          forkJoin({ summary: this.platformApi.dashboardSummary(), metrics: this.platformApi.deploymentMetrics() }),
        ),
        retryWhileTransient(REFRESH_MS, (err) => this.showError(err)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: ({ summary, metrics }) => {
          this.summary.set(summary);
          this.metrics.set(metrics);
          this.error.set(null);
          this.loading.set(false);
        },
        error: (err: ApiError) => this.showError(err),
      });
  }

  /** 850 -> "850 ms", 4200 -> "4.2 s", 95000 -> "1 min 35 s". */
  protected duration(ms: number | null): string {
    if (ms === null) return '—';
    if (ms < 1000) return `${ms} ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
    const minutes = Math.floor(ms / 60_000);
    return `${minutes} min ${Math.round((ms % 60_000) / 1000)} s`;
  }

  protected percent(rate: number | null): string {
    return rate === null ? '—' : `${Math.round(rate * 100)}%`;
  }

  private showError(err: ApiError): void {
    this.error.set(err);
    this.loading.set(false);
  }
}
