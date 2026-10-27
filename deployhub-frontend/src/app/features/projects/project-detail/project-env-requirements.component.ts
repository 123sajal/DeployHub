import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';

import { ApiError, EnvRequirementItem, EnvRequirements, EnvVarKind } from '../../../core/models/api.models';
import { ProjectApiService } from '../../../core/services/api/project-api.service';

const KIND_LABEL: Record<EnvVarKind, string> = {
  SECRET: 'secret',
  DATABASE: 'database',
  DATABASE_ALIAS: 'database',
  SETTING: 'setting',
  PLATFORM: 'set by DeployHub',
  EXTERNAL: 'key from another service',
  LOCAL_ONLY: 'address',
  UNKNOWN: 'setting',
};

/**
 * "What this app needs": the environment variables the app uses, read from its repository (.env.example, the code,
 * deployhub.json), what is still missing, and Fill in automatically for everything DeployHub can fill by itself.
 */
@Component({
  selector: 'app-project-env-requirements',
  imports: [ButtonModule],
  template: `
    <div class="needs">
      <div class="head">
        <h3><i class="pi pi-list-check" aria-hidden="true"></i> What this app needs</h3>
        @if (summary(); as s) {
          <span class="dh-muted">{{ s }}</span>
        }
      </div>

      @if (loading()) {
        <p class="dh-muted"><i class="pi pi-spin pi-spinner" aria-hidden="true"></i> Reading the repository…</p>
      } @else if (error(); as err) {
        <p class="dh-muted">Couldn't check the repository: {{ err.detail }}</p>
      } @else if (data(); as d) {
        @if (d.databaseSuggested) {
          <p class="db"><i class="pi pi-database" aria-hidden="true"></i>
            This app uses a database: click <strong>Add MySQL database</strong> in the Database card. It fills in the
            database settings for you.</p>
        }
        @if (d.autoFillable.length) {
          <div class="fill">
            <p-button [label]="'Fill in automatically (' + d.autoFillable.length + ')'" icon="pi pi-bolt" size="small"
                      [loading]="filling()" (onClick)="fillIn()" />
            <span class="dh-muted">Generates secrets and uses the example values. You never need to know them.</span>
          </div>
        }
        @if (missing().length) {
          <ul class="items">
            @for (item of missing(); track item.name) {
              <li>
                <code>{{ item.name }}</code>
                <span class="tag">{{ kindLabel(item) }}</span>
                @if (!item.required) {
                  <span class="tag optional">optional</span>
                }
                <p class="help dh-muted">{{ item.help }} <span class="src">({{ item.source }})</span></p>
              </li>
            }
          </ul>
        } @else if (d.items.length) {
          <p class="ok"><i class="pi pi-check-circle" aria-hidden="true"></i> Everything the app needs is set.</p>
        }
        @for (note of d.notes; track note) {
          <p class="dh-muted note">{{ note }}</p>
        }
      }
    </div>
  `,
  styles: `
    .needs { border: 1px solid var(--dh-border); border-radius: 8px; padding: 0.75rem 1rem; margin-bottom: 1rem; }
    .head { display: flex; align-items: baseline; justify-content: space-between; gap: 0.5rem; flex-wrap: wrap; }
    h3 { margin: 0 0 0.5rem; font-size: 0.95rem; }
    .fill { display: flex; align-items: center; gap: 0.5rem 0.75rem; flex-wrap: wrap; margin: 0.25rem 0 0.5rem; font-size: 0.82rem; }
    .items { list-style: none; margin: 0.25rem 0 0; padding: 0; }
    .items li { padding: 0.4rem 0; border-top: 1px solid var(--dh-border); }
    .items code { font-weight: 600; }
    .tag { margin-left: 0.4rem; font-size: 0.72rem; padding: 0.05rem 0.4rem; border-radius: 999px; border: 1px solid var(--dh-border-strong); }
    .tag.optional { opacity: 0.7; }
    .help { margin: 0.2rem 0 0; font-size: 0.8rem; }
    .src { opacity: 0.7; }
    .db, .ok { font-size: 0.85rem; margin: 0.25rem 0 0.5rem; }
    .note { font-size: 0.75rem; margin: 0.4rem 0 0; }
  `,
})
export class ProjectEnvRequirementsComponent {
  readonly projectId = input.required<number>();
  /** Bumped by the page when variables or the database changed. */
  readonly refresh = input(0);
  /** The latest list, for the page's check before deploying. */
  readonly loaded = output<EnvRequirements>();
  /** Fill in automatically changed the variables. */
  readonly changed = output<void>();

  private readonly api = inject(ProjectApiService);
  private readonly messages = inject(MessageService);

  protected readonly data = signal<EnvRequirements | null>(null);
  protected readonly loading = signal(true);
  protected readonly filling = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  /** Missing ones first: required before optional; a set or provided variable needs nothing. */
  protected readonly missing = computed(() =>
    (this.data()?.items ?? []).filter((i) => i.status === 'MISSING').sort((a, b) => Number(b.required) - Number(a.required)),
  );
  protected readonly summary = computed(() => {
    const d = this.data();
    if (!d || !d.items.length) return null;
    const relevant = d.items.filter((i) => i.status !== 'PROVIDED');
    const set = relevant.filter((i) => i.status === 'SET').length;
    return `${set} of ${relevant.length} set${d.missingRequired ? ` · ${d.missingRequired} required missing` : ''}`;
  });

  constructor() {
    effect(() => {
      this.projectId();
      this.refresh();
      untracked(() => this.load());
    });
  }

  protected kindLabel(item: EnvRequirementItem): string {
    return KIND_LABEL[item.kind];
  }

  private load(): void {
    this.error.set(null);
    this.api.envRequirements(this.projectId()).subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
        this.loaded.emit(d);
      },
      error: (err: ApiError) => {
        this.error.set(err);
        this.loading.set(false);
      },
    });
  }

  protected fillIn(): void {
    const d = this.data();
    if (!d?.autoFillable.length) return;
    const names = d.autoFillable;
    this.filling.set(true);
    this.api.fillEnvRequirements(this.projectId(), names).subscribe({
      next: (updated) => {
        this.filling.set(false);
        this.data.set(updated);
        this.loaded.emit(updated);
        this.changed.emit();
        this.messages.add({ severity: 'success', summary: 'Filled in', detail: names.join(', ') + '. Applies at the next deployment.' });
      },
      error: (err: ApiError) => {
        this.filling.set(false);
        this.messages.add({ severity: 'error', summary: err.title, detail: err.detail });
      },
    });
  }
}
