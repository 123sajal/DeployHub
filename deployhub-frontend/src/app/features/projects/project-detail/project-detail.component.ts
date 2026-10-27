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
import { ApiError, BillingSummary, Deployment, EnvRequirementItem, EnvRequirements, Project, PublicLink } from '../../../core/models/api.models';
import { BillingApiService } from '../../../core/services/api/billing-api.service';
import { DeploymentApiService } from '../../../core/services/api/deployment-api.service';
import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { formatPrice } from '../../../core/utils/money';
import { appTypeLabel } from '../../../core/utils/deployment-status';
import { ProjectDatabaseComponent } from './project-database.component';
import { ProjectPublicLinkComponent } from './project-public-link.component';
import { ProjectEnvRequirementsComponent } from './project-env-requirements.component';
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
    ProjectEnvRequirementsComponent,
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
  private readonly billingApi = inject(BillingApiService);
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
  /** Price and credits for the "paid project" line; null until loaded (or when it couldn't be). */
  protected readonly billing = signal<BillingSummary | null>(null);
  protected readonly price = computed(() => {
    const b = this.billing();
    return b ? formatPrice(b.pricePerDeploy, b.currency) : '';
  });
  /** Bumped when the database card changed environment variables, so their card reloads. */
  protected readonly envVersion = signal(0);
  /** What the app needs (from its repository); checked before deploying. Null until loaded. */
  protected readonly requirements = signal<EnvRequirements | null>(null);
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
    this.billingApi.summary().subscribe({ next: (b) => this.billing.set(b), error: () => this.billing.set(null) });
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

  /** Checks first that the app's required settings are there; then deploys. */
  protected deploy(): void {
    if (this.deploying()) {
      return; // a second click while the first request is in flight does nothing
    }
    const r = this.requirements();
    const missing = r?.items.filter((i) => i.status === 'MISSING' && i.required) ?? [];
    if (r && missing.length) {
      this.confirmMissingSettings(r, missing);
      return;
    }
    this.deployNow();
  }

  /** Before a deploy that would likely crash: offer to fill in what DeployHub can, or deploy anyway. */
  private confirmMissingSettings(r: EnvRequirements, missing: EnvRequirementItem[]): void {
    const fillable = missing.filter((i) => r.autoFillable.includes(i.name)).map((i) => i.name);
    const manual = missing.filter((i) => !r.autoFillable.includes(i.name));
    const needsDatabase = manual.some((i) => i.kind === 'DATABASE');
    const others = manual.filter((i) => i.kind !== 'DATABASE').map((i) => i.name);
    const parts: string[] = [];
    if (fillable.length) parts.push(`DeployHub can fill in ${fillable.join(', ')} automatically.`);
    if (needsDatabase) parts.push('The app needs a database: use Add MySQL database in the Database card.');
    if (others.length) parts.push(`You need to add ${others.join(', ')} under Environment variables.`);
    this.confirm.confirm({
      header: manual.length ? 'Some settings are still missing' : 'Fill in the missing settings?',
      message: parts.join(' ') + (manual.length ? ' Without them the app will probably not start.' : ''),
      icon: 'pi pi-list-check',
      acceptLabel: manual.length ? (fillable.length ? 'Fill in what you can & deploy anyway' : 'Deploy anyway') : 'Fill in & deploy',
      rejectLabel: 'Cancel',
      rejectButtonProps: { severity: 'secondary', text: true },
      accept: () => {
        if (!fillable.length) {
          this.deployNow();
          return;
        }
        this.deploying.set(true);
        this.projectApi.fillEnvRequirements(Number(this.id()), fillable).subscribe({
          next: (updated) => {
            this.requirements.set(updated);
            this.envVersion.update((v) => v + 1);
            this.deploying.set(false);
            this.deployNow();
          },
          error: (err: ApiError) => {
            this.deploying.set(false);
            this.messages.add({ severity: 'error', summary: err.title, detail: err.detail });
          },
        });
      },
    });
  }

  private deployNow(): void {
    if (this.deploying()) {
      return;
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
        if (err.status === 402) {
          // The free project is used and there's no paid deploy left: the payment page takes over.
          this.router.navigate(['/app/projects/payment'], { queryParams: { project: this.id() } });
          return;
        }
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
