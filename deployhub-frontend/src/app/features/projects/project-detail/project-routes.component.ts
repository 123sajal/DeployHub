import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';

import { ApiError, Project, ProjectRoute } from '../../../core/models/api.models';
import { ProjectApiService } from '../../../core/services/api/project-api.service';

const PREFIX = /^\/[A-Za-z0-9._~/-]+$/;

/**
 * Path prefixes of this project's address that go to another project, e.g. /api to the backend. The
 * frontend then calls "/api/..." on its own address: no backend URL in its code and no CORS setup.
 */
@Component({
  selector: 'app-project-routes',
  imports: [FormsModule, ButtonModule, InputTextModule, SelectModule],
  template: `
    <h2 class="dh-card-title">Routes</h2>
    <p class="dh-muted hint">
      Send a path of this project's address to another project. For example <code>/api</code> to your backend:
      your frontend calls <code>/api/...</code> and needs no backend URL or CORS setup. Saved routes apply at once.
    </p>

    @for (route of routes(); track $index) {
      <div class="row">
        <input pInputText class="mono prefix" [(ngModel)]="route.pathPrefix" placeholder="/api"
               [attr.aria-label]="'Path prefix ' + ($index + 1)" [attr.aria-invalid]="!validPrefix(route.pathPrefix)" />
        <i class="pi pi-arrow-right dh-muted" aria-hidden="true"></i>
        <p-select [options]="targets()" [(ngModel)]="route.targetProjectId" optionLabel="name" optionValue="id"
                  placeholder="Project" [ariaLabel]="'Target project ' + ($index + 1)" class="target" />
        <p-button icon="pi pi-times" [text]="true" severity="secondary" size="small"
                  [ariaLabel]="'Remove route ' + ($index + 1)" (onClick)="remove($index)" />
      </div>
    } @empty {
      <p class="dh-muted">No routes. Everything goes to this project's own app.</p>
    }

    @if (error(); as err) {
      <p class="dh-error" role="alert">{{ err.detail }}</p>
    }

    <div class="actions">
      <p-button label="Add route" icon="pi pi-plus" [text]="true" size="small" (onClick)="add()"
                [disabled]="targets().length === 0" />
      <p-button label="Save routes" icon="pi pi-check" size="small" [loading]="saving()" (onClick)="save()" />
    </div>
  `,
  styles: `
    .hint { margin: 0 0 0.75rem; font-size: 0.85rem; }
    .row { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.5rem; flex-wrap: wrap; }
    .prefix { width: 9rem; }
    .target { flex: 1 1 10rem; min-width: 0; }
    .actions { display: flex; justify-content: space-between; margin-top: 0.5rem; }
  `,
})
export class ProjectRoutesComponent implements OnInit {
  readonly projectId = input.required<number>();

  private readonly api = inject(ProjectApiService);
  private readonly messages = inject(MessageService);

  protected readonly routes = signal<ProjectRoute[]>([]);
  private readonly projects = signal<Project[]>([]);
  protected readonly targets = computed(() => this.projects().filter((p) => p.id !== this.projectId()));
  protected readonly saving = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  ngOnInit(): void {
    this.api.listRoutes(this.projectId()).subscribe({
      next: (list) => this.routes.set(list.map((r) => ({ ...r }))),
      error: (err: ApiError) => this.error.set(err),
    });
    this.api.list({ size: 100 }).subscribe({ next: (page) => this.projects.set(page.items) });
  }

  protected validPrefix(prefix: string): boolean {
    return PREFIX.test(prefix ?? '');
  }

  protected add(): void {
    this.routes.update((list) => [...list, { pathPrefix: '/api', targetProjectId: this.targets()[0].id }]);
  }

  protected remove(index: number): void {
    this.routes.update((list) => list.filter((_, i) => i !== index));
  }

  protected save(): void {
    if (this.routes().some((r) => !this.validPrefix(r.pathPrefix) || !r.targetProjectId)) {
      this.error.set({ status: 400, title: 'Invalid route', detail: 'Each route needs a path like /api and a project.', fieldErrors: {} });
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api.replaceRoutes(this.projectId(), this.routes()).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.routes.set(saved.map((r) => ({ ...r })));
        this.messages.add({ severity: 'success', summary: 'Routes saved', detail: 'The project address is updated' });
      },
      error: (err: ApiError) => {
        this.saving.set(false);
        this.error.set({ ...err, detail: err.fieldErrors['routes'] ?? err.detail });
      },
    });
  }
}
