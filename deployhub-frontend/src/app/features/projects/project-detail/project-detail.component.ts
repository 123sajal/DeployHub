import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { switchMap, timer } from 'rxjs';

import { PageStateComponent } from '../../../core/components/shared/page-state.component';
import { SimulatedBadgeComponent } from '../../../core/components/shared/simulated-badge.component';
import { StatusBadgeComponent } from '../../../core/components/shared/status-badge.component';
import { ApiError, Deployment, Project, PublicLink } from '../../../core/models/api.models';
import { DeploymentApiService } from '../../../core/services/api/deployment-api.service';
import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { appTypeLabel } from '../../../core/utils/deployment-status';
import { ProjectDatabaseComponent } from './project-database.component';
import { ProjectPublicLinkComponent } from './project-public-link.component';
import { ProjectEnvVarsComponent } from './project-env-vars.component';
import { ProjectRoutesComponent } from './project-routes.component';
import { newIdempotencyKey } from '../../../core/utils/idempotency-key';
import { retryWhileTransient } from '../../../core/utils/polling';

const REFRESH_MS = 4000;

@Component({
  selector: 'app-project-detail',
  imports: [
    RouterLink,
    DatePipe,
    ButtonModule,
    PageStateComponent,
    StatusBadgeComponent,
    SimulatedBadgeComponent,
    ProjectEnvVarsComponent,
    ProjectRoutesComponent,
    ProjectDatabaseComponent,
    ProjectPublicLinkComponent,
  ],
  templateUrl: './project-detail.component.html',
  styleUrl: './project-detail.component.scss',
})
export class ProjectDetailComponent implements OnInit {
  readonly id = input.required<string>();

  private readonly projectApi = inject(ProjectApiService);
  private readonly deploymentApi = inject(DeploymentApiService);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly appTypeLabel = appTypeLabel;
  protected readonly project = signal<Project | null>(null);
  protected readonly deployments = signal<Deployment[] | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly deploying = signal(false);
  /** Bumped when the database card changed environment variables, so their card reloads. */
  protected readonly envVersion = signal(0);
  /**
   * What "Open app" opens: the public link while it is on, else the project's address on this computer, else the
   * live deployment's own address (before the project has one).
   */
  protected readonly appUrl = computed(() => {
    const p = this.project();
    if (p?.publicLinkEnabled && p.publicLink) return p.publicLink;
    return p?.localUrl ?? this.deployments()?.find((d) => d.status === 'LIVE' && d.appUrl)?.appUrl ?? null;
  });

  /** The public link card switched the link on or off: "Open app" follows it at once. */
  protected onPublicLinkChanged(link: PublicLink): void {
    this.project.update((p) => (p ? { ...p, publicLinkEnabled: link.enabled, publicLink: link.url } : p));
  }

  ngOnInit(): void {
    this.load();
    // While the API is briefly unreachable, keep showing the last list and keep polling.
    timer(0, REFRESH_MS)
      .pipe(
        switchMap(() => this.deploymentApi.list({ projectId: Number(this.id()), size: 10 })),
        retryWhileTransient(REFRESH_MS, () => {}),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({ next: (page) => this.deployments.set(page.items), error: () => this.deployments.set([]) });
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.projectApi.get(Number(this.id())).subscribe({
      next: (p) => {
        this.project.set(p);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.error.set(err);
        this.loading.set(false);
      },
    });
  }

  protected deploy(): void {
    if (this.deploying()) {
      return; // a second click while the first request is in flight does nothing
    }
    this.deploying.set(true);
    // One key per click. If this request is retried (e.g. the CSRF retry), the server sees the same
    // key and returns the same deployment instead of creating a duplicate.
    this.deploymentApi.create(Number(this.id()), newIdempotencyKey()).subscribe({
      next: (d) => {
        this.messages.add({ severity: 'info', summary: 'Deployment queued', detail: `#${d.id} (simulated)` });
        this.router.navigate(['/app/deployments', d.id]);
      },
      error: (err: ApiError) => {
        this.deploying.set(false);
        this.messages.add({ severity: 'error', summary: err.title, detail: err.detail });
      },
    });
  }

  protected confirmDelete(project: Project): void {
    this.confirm.confirm({
      header: 'Delete project?',
      message: `This permanently deletes "${project.name}", its deployment history and its database (if any).`,
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Delete',
      rejectLabel: 'Cancel',
      acceptButtonProps: { severity: 'danger' },
      rejectButtonProps: { severity: 'secondary', text: true },
      accept: () =>
        this.projectApi.delete(project.id).subscribe({
          next: () => {
            this.messages.add({ severity: 'success', summary: 'Project deleted', detail: project.name });
            this.router.navigate(['/app/projects']);
          },
          error: (err: ApiError) => this.messages.add({ severity: 'error', summary: err.title, detail: err.detail }),
        }),
    });
  }
}
