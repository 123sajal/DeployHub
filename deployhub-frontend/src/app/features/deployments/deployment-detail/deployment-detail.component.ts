import { DatePipe } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { Subscription, forkJoin, switchMap, takeWhile, timer } from 'rxjs';

import { PageStateComponent } from '../../../core/components/shared/page-state.component';
import { SimulatedBadgeComponent } from '../../../core/components/shared/simulated-badge.component';
import { StatusBadgeComponent } from '../../../core/components/shared/status-badge.component';
import { ApiError, Deployment, DeploymentHistory, DeploymentStatus } from '../../../core/models/api.models';
import { DeploymentApiService } from '../../../core/services/api/deployment-api.service';
import { PIPELINE, isInProgress, statusLabel } from '../../../core/utils/deployment-status';
import { newIdempotencyKey } from '../../../core/utils/idempotency-key';
import { retryWhileTransient } from '../../../core/utils/polling';
import { LogViewerComponent } from '../../logs/log-viewer/log-viewer.component';

type StepState = 'done' | 'current' | 'failed' | 'pending';

const POLL_MS = 2000;

@Component({
  selector: 'app-deployment-detail',
  imports: [RouterLink, DatePipe, ButtonModule, MessageModule, PageStateComponent, StatusBadgeComponent, SimulatedBadgeComponent, LogViewerComponent],
  templateUrl: './deployment-detail.component.html',
  styleUrl: './deployment-detail.component.scss',
})
export class DeploymentDetailComponent {
  readonly id = input.required<string>();

  private readonly deploymentApi = inject(DeploymentApiService);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private polling?: Subscription;

  protected readonly deployment = signal<Deployment | null>(null);
  protected readonly history = signal<DeploymentHistory | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly cancelling = signal(false);
  protected readonly rollingBack = signal(false);
  protected readonly deploymentId = computed(() => Number(this.id()));

  /** Maps the current status onto the QUEUED → BUILDING → STARTING → LIVE pipeline. */
  protected readonly steps = computed(() => {
    const d = this.deployment();
    if (!d) return [];
    const reached = this.reachedIndex(d);
    const stopped = d.status === 'FAILED' || d.status === 'CANCELLED';
    return PIPELINE.map((status, i): { label: string; state: StepState } => {
      let state: StepState = 'pending';
      if (i < reached) state = 'done';
      else if (i === reached) state = stopped ? 'failed' : d.status === 'LIVE' || d.status === 'SUPERSEDED' ? 'done' : 'current';
      return { label: statusLabel(status), state };
    });
  });

  constructor() {
    // Load again when the id changes: the router reuses this component going from one deployment to
    // another (e.g. straight to the new deployment after a rollback).
    effect(() => {
      this.id();
      untracked(() => {
        this.deployment.set(null);
        this.history.set(null);
        this.load();
      });
    });
  }

  /**
   * Polls every 2s and stops by itself once the deployment reaches a final state. Survives the API
   * being briefly unreachable; stops on errors that won't go away (e.g. 404).
   */
  protected load(): void {
    this.polling?.unsubscribe();
    this.loading.set(this.deployment() === null);
    this.error.set(null);
    this.polling = timer(0, POLL_MS)
      .pipe(
        switchMap(() =>
          forkJoin({
            deployment: this.deploymentApi.get(this.deploymentId()),
            history: this.deploymentApi.history(this.deploymentId()),
          }),
        ),
        retryWhileTransient(POLL_MS, (err) => this.showError(err)),
        takeWhile(({ deployment }) => isInProgress(deployment.status), true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: ({ deployment, history }) => {
          this.deployment.set(deployment);
          this.history.set(history);
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

  protected confirmCancel(): void {
    this.confirm.confirm({
      header: 'Cancel deployment?',
      message: 'The deployment will stop and be marked CANCELLED. This cannot be undone.',
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Cancel deployment',
      rejectLabel: 'Keep running',
      acceptButtonProps: { severity: 'danger' },
      rejectButtonProps: { severity: 'secondary', text: true },
      accept: () => this.cancel(),
    });
  }

  private cancel(): void {
    this.cancelling.set(true);
    this.deploymentApi.cancel(this.deploymentId()).subscribe({
      next: (d) => {
        this.cancelling.set(false);
        this.deployment.set(d);
        this.deploymentApi.history(d.id).subscribe((h) => this.history.set(h));
        this.messages.add({ severity: 'warn', summary: 'Deployment cancelled', detail: `#${d.id}` });
      },
      error: (err: ApiError) => {
        this.cancelling.set(false);
        // 409 means the worker moved it on (e.g. it went LIVE) before our cancel arrived.
        this.messages.add({ severity: 'error', summary: err.title, detail: err.detail });
        this.load();
      },
    });
  }

  protected confirmRollback(d: Deployment): void {
    this.confirm.confirm({
      header: `Roll back to #${d.id}?`,
      message:
        `A new deployment will redeploy ${d.commitRef ? 'commit ' + d.commitRef : 'this deployment'} of ` +
        `${d.projectName}. When it goes live it replaces the current live deployment.`,
      icon: 'pi pi-history',
      acceptLabel: 'Roll back',
      rejectLabel: 'Cancel',
      rejectButtonProps: { severity: 'secondary', text: true },
      accept: () => this.rollback(d),
    });
  }

  private rollback(d: Deployment): void {
    if (this.rollingBack()) return;
    this.rollingBack.set(true);
    // One key per confirmed click, so a retried request can't queue two rollbacks.
    this.deploymentApi.rollback(d.id, newIdempotencyKey()).subscribe({
      next: (created) => {
        this.rollingBack.set(false);
        this.messages.add({ severity: 'info', summary: 'Rollback queued', detail: `#${created.id} redeploys #${d.id}` });
        this.router.navigate(['/app/deployments', created.id]);
      },
      error: (err: ApiError) => {
        this.rollingBack.set(false);
        this.messages.add({ severity: 'error', summary: err.title, detail: err.detail });
      },
    });
  }

  /** "user:42" -> "You", "worker:host-1a2b" -> "Worker host-1a2b", "system" -> "System". */
  protected actorLabel(actor: string): string {
    if (actor.startsWith('user:')) return 'You';
    if (actor.startsWith('worker:')) return `Worker ${actor.slice('worker:'.length)}`;
    return 'System';
  }

  private reachedIndex(d: Deployment): number {
    const idx = PIPELINE.indexOf(d.status as DeploymentStatus);
    if (idx >= 0) return idx;
    if (d.status === 'SUPERSEDED') return PIPELINE.length - 1;
    // FAILED / CANCELLED: the last in-progress status in the recorded history is where it stopped.
    const reached = (this.history()?.transitions ?? [])
      .map((t) => PIPELINE.indexOf(t.toStatus))
      .filter((i) => i >= 0);
    return reached.length ? Math.max(...reached) : d.startedAt ? 1 : 0;
  }
}
