import { DatePipe } from '@angular/common';
import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';

import { ApiError, EnvVar } from '../../../core/models/api.models';
import { ProjectApiService } from '../../../core/services/api/project-api.service';

/** Matches the backend's rule: a letter or _, then letters, digits or _. */
const NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;

/**
 * A project's environment variables. Values are write-only: they are sent once, encrypted on the server,
 * and never shown again. To change one, type a new value.
 */
@Component({
  selector: 'app-project-env-vars',
  imports: [DatePipe, ReactiveFormsModule, ButtonModule, InputTextModule],
  template: `
    <h2 class="dh-card-title">Environment variables</h2>
    <p class="dh-muted hint">
      <i class="pi pi-lock" aria-hidden="true"></i>
      Encrypted on the server and never shown again. Changes apply at the next deployment.
    </p>

    @if (error(); as err) {
      <p class="dh-error" role="alert">{{ err.detail }}</p>
    }

    @if (variables().length > 0) {
      <ul class="vars">
        @for (v of variables(); track v.name) {
          <li>
            <code>{{ v.name }}</code>
            <span class="dh-muted mask" aria-label="value hidden">••••••••</span>
            <span class="dh-muted when">updated {{ v.updatedAt | date: 'MMM d, HH:mm' }}</span>
            <p-button icon="pi pi-pencil" [text]="true" size="small" [ariaLabel]="'Replace ' + v.name"
                      (onClick)="startReplace(v.name)" />
            <p-button icon="pi pi-trash" severity="danger" [text]="true" size="small" [ariaLabel]="'Delete ' + v.name"
                      (onClick)="confirmDelete(v.name)" />
          </li>
        }
      </ul>
    } @else if (!loading()) {
      <p class="dh-muted">None yet. Add database settings, API keys and similar here.</p>
    }

    <form class="add" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <input pInputText formControlName="name" class="mono" placeholder="NAME" aria-label="Variable name"
             autocomplete="off" [attr.aria-invalid]="form.controls.name.invalid && form.controls.name.touched" />
      <input pInputText formControlName="value" type="password" placeholder="value" aria-label="Variable value"
             autocomplete="new-password" />
      <p-button type="submit" [label]="replacing() ? 'Replace' : 'Add'" icon="pi pi-check" size="small"
                [loading]="saving()" />
    </form>
    @if (form.controls.name.invalid && form.controls.name.touched) {
      <small class="dh-error" role="alert">Use letters, digits and _, starting with a letter or _.</small>
    }
  `,
  styles: `
    .hint { display: flex; gap: 0.4rem; align-items: center; margin: 0 0 0.75rem; font-size: 0.85rem; }
    .vars { list-style: none; margin: 0 0 0.75rem; padding: 0; }
    .vars li { display: flex; align-items: center; gap: 0.6rem; padding: 0.3rem 0; border-bottom: 1px solid var(--dh-border); }
    .vars code { font-weight: 600; }
    .mask { letter-spacing: 0.1em; }
    .when { margin-left: auto; font-size: 0.78rem; }
    .add { display: flex; gap: 0.5rem; flex-wrap: wrap; }
    .add input { flex: 1 1 140px; min-width: 0; }
  `,
})
export class ProjectEnvVarsComponent {
  readonly projectId = input.required<number>();
  /** Bumped by the page when something else changed the variables (e.g. adding a database). */
  readonly refresh = input(0);
  /** A variable was added, replaced or deleted (so "What this app needs" can update). */
  readonly changed = output<void>();

  private readonly api = inject(ProjectApiService);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly fb = inject(FormBuilder);

  protected readonly variables = signal<EnvVar[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly replacing = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(100), Validators.pattern(NAME)]],
    value: ['', [Validators.required, Validators.maxLength(2000)]],
  });

  constructor() {
    effect(() => {
      this.projectId();
      this.refresh();
      untracked(() => this.load());
    });
  }

  private load(): void {
    this.api.listEnv(this.projectId()).subscribe({
      next: (list) => {
        this.variables.set(list);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.error.set(err);
        this.loading.set(false);
      },
    });
  }

  protected startReplace(name: string): void {
    this.replacing.set(true);
    this.form.setValue({ name, value: '' });
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, value } = this.form.getRawValue();
    this.saving.set(true);
    this.error.set(null);
    this.api.setEnv(this.projectId(), name.trim(), value).subscribe({
      next: () => {
        this.saving.set(false);
        this.replacing.set(false);
        this.form.reset();
        this.messages.add({ severity: 'success', summary: `${name} saved`, detail: 'Applies at the next deployment' });
        this.load();
        this.changed.emit();
      },
      error: (err: ApiError) => {
        this.saving.set(false);
        this.error.set({ ...err, detail: err.fieldErrors['name'] ?? err.fieldErrors['value'] ?? err.detail });
      },
    });
  }

  protected confirmDelete(name: string): void {
    this.confirm.confirm({
      header: `Delete ${name}?`,
      message: 'The app keeps its current value until the next deployment.',
      icon: 'pi pi-trash',
      acceptLabel: 'Delete',
      rejectLabel: 'Cancel',
      acceptButtonProps: { severity: 'danger' },
      rejectButtonProps: { severity: 'secondary', text: true },
      accept: () =>
        this.api.deleteEnv(this.projectId(), name).subscribe({
          next: () => {
            this.load();
            this.changed.emit();
          },
          error: (err: ApiError) => this.error.set(err),
        }),
    });
  }
}
