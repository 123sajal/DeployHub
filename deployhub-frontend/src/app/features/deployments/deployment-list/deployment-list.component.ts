import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { SelectModule } from 'primeng/select';
import { Subscription, switchMap, timer } from 'rxjs';

import { PageStateComponent } from '../../../core/components/shared/page-state.component';
import { SimulatedBadgeComponent } from '../../../core/components/shared/simulated-badge.component';
import { StatusBadgeComponent } from '../../../core/components/shared/status-badge.component';
import { ApiError, Deployment, DeploymentStatus, Page } from '../../../core/models/api.models';
import { DeploymentApiService } from '../../../core/services/api/deployment-api.service';
import { DEPLOYMENT_STATUSES, statusLabel } from '../../../core/utils/deployment-status';
import { retryWhileTransient } from '../../../core/utils/polling';

const PAGE_SIZE = 15;
const REFRESH_MS = 5000;

@Component({
  selector: 'app-deployment-list',
  imports: [RouterLink, DatePipe, FormsModule, ButtonModule, SelectModule, PageStateComponent, StatusBadgeComponent, SimulatedBadgeComponent],
  templateUrl: './deployment-list.component.html',
  styles: `
    .head p { margin: 0.25rem 0 0; }
    .filters { display: flex; gap: 0.75rem; flex-wrap: wrap; align-items: center; }
    .reason { max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--dh-danger); font-size: 0.82rem; }
  `,
})
export class DeploymentListComponent implements OnInit {
  /** Query params, bound by withComponentInputBinding(). */
  readonly status = input<DeploymentStatus>();
  readonly projectId = input<string>();

  private readonly deploymentApi = inject(DeploymentApiService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private polling?: Subscription;

  protected readonly statusOptions = DEPLOYMENT_STATUSES.map((s) => ({ label: statusLabel(s), value: s }));
  protected readonly page = signal(0);
  protected readonly result = signal<Page<Deployment> | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.polling?.unsubscribe();
    this.loading.set(this.result() === null);
    this.error.set(null);
    this.polling = timer(0, REFRESH_MS)
      .pipe(
        switchMap(() =>
          this.deploymentApi.list({
            status: this.status() ?? null,
            projectId: this.projectId() ? Number(this.projectId()) : null,
            page: this.page(),
            size: PAGE_SIZE,
          }),
        ),
        retryWhileTransient(REFRESH_MS, (err) => this.showError(err)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (r) => {
          this.result.set(r);
          this.error.set(null);
          this.loading.set(false);
        },
        error: (err: ApiError) => this.showError(err),
      });
  }

  private showError(err: ApiError): void {
    this.error.set(err);
    this.loading.set(false);
  }

  /** Filters live in the URL so a filtered view can be bookmarked or linked from the dashboard. */
  protected filterStatus(status: DeploymentStatus | null): void {
    this.page.set(0);
    this.result.set(null);
    this.router.navigate([], { queryParams: { status }, queryParamsHandling: 'merge' }).then(() => this.load());
  }

  protected clearProject(): void {
    this.router.navigate([], { queryParams: { projectId: null }, queryParamsHandling: 'merge' }).then(() => this.load());
  }

  protected goTo(page: number): void {
    this.page.set(page);
    this.load();
  }
}
