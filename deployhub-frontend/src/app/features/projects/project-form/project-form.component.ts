import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';

import { PageStateComponent } from '../../../core/components/shared/page-state.component';
import { ApiError, ApplicationType, Project, ProjectRequest } from '../../../core/models/api.models';
import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { APP_TYPE_OPTIONS } from '../../../core/utils/deployment-status';
import { BRANCH_PATTERN, PROJECT_NAME_PATTERN, repositoryUrlValidator } from './project-validators';

const DEFAULT_COMMANDS: Record<ApplicationType, { build: string; start: string }> = {
  JAVA_SPRING_BOOT: { build: './mvnw -DskipTests package', start: 'java -jar target/app.jar' },
  NODE_JS: { build: 'npm ci && npm run build', start: 'node server.js' },
  STATIC_SITE: { build: 'npm ci && npm run build', start: 'serve dist' },
  PYTHON: { build: 'pip install -r requirements.txt', start: 'gunicorn app:app' },
  DOCKERFILE: { build: '', start: 'docker run (from Dockerfile CMD)' },
};

@Component({
  selector: 'app-project-form',
  imports: [ReactiveFormsModule, RouterLink, ButtonModule, InputTextModule, SelectModule, MessageModule, PageStateComponent],
  templateUrl: './project-form.component.html',
  styleUrl: './project-form.component.scss',
})
export class ProjectFormComponent implements OnInit {
  /** Bound from the :id route param; absent when creating. */
  readonly id = input<string>();

  private readonly fb = inject(FormBuilder);
  private readonly projectApi = inject(ProjectApiService);
  private readonly router = inject(Router);
  private readonly messages = inject(MessageService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly isEdit = computed(() => !!this.id());
  protected readonly appTypeOptions = APP_TYPE_OPTIONS;
  protected readonly loading = signal(false);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly saving = signal(false);
  protected readonly submitError = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(50), Validators.pattern(PROJECT_NAME_PATTERN)]],
    repositoryUrl: ['', [Validators.required, Validators.maxLength(300), repositoryUrlValidator]],
    branch: ['main', [Validators.required, Validators.maxLength(100), Validators.pattern(BRANCH_PATTERN)]],
    appType: this.fb.control<ApplicationType | null>(null, Validators.required),
    buildCommand: ['', [Validators.maxLength(500)]],
    startCommand: ['', [Validators.required, Validators.maxLength(500)]],
  });

  ngOnInit(): void {
    if (this.isEdit()) {
      this.loadProject();
    } else {
      this.form.controls.appType.valueChanges
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((type) => this.suggestCommands(type));
    }
  }

  protected loadProject(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.projectApi.get(Number(this.id())).subscribe({
      next: (p: Project) => {
        this.form.setValue({
          name: p.name,
          repositoryUrl: p.repositoryUrl,
          branch: p.branch,
          appType: p.appType,
          buildCommand: p.buildCommand ?? '',
          startCommand: p.startCommand,
        });
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.loadError.set(err);
        this.loading.set(false);
      },
    });
  }

  protected showError(field: keyof typeof this.form.controls): boolean {
    const control = this.form.controls[field];
    return control.invalid && (control.touched || control.dirty);
  }

  protected errorText(field: keyof typeof this.form.controls): string {
    const errors = this.form.controls[field].errors ?? {};
    if (errors['server']) return errors['server'];
    if (errors['required']) return 'This field is required';
    if (errors['minlength']) return `Must be at least ${errors['minlength'].requiredLength} characters`;
    if (errors['maxlength']) return `Must be at most ${errors['maxlength'].requiredLength} characters`;
    if (errors['repositoryUrl']) return errors['repositoryUrl'];
    if (errors['pattern']) {
      return field === 'name'
        ? 'Use lowercase letters, digits and hyphens (no leading/trailing hyphen)'
        : 'Not a valid branch name';
    }
    return 'Invalid value';
  }

  protected submit(): void {
    this.submitError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const raw = this.form.getRawValue();
    const request: ProjectRequest = {
      ...raw,
      appType: raw.appType!,
      repositoryUrl: raw.repositoryUrl.trim(),
      buildCommand: raw.buildCommand.trim() || null,
    };
    this.saving.set(true);
    const call$ = this.isEdit()
      ? this.projectApi.update(Number(this.id()), request)
      : this.projectApi.create(request);
    call$.subscribe({
      next: (project) => {
        this.messages.add({
          severity: 'success',
          summary: this.isEdit() ? 'Project updated' : 'Project created',
          detail: project.name,
        });
        this.router.navigate(['/app/projects', project.id]);
      },
      error: (err: ApiError) => {
        this.saving.set(false);
        this.applyServerErrors(err);
      },
    });
  }

  /** Puts backend validation messages on the matching form controls. */
  private applyServerErrors(err: ApiError): void {
    let mapped = false;
    for (const [field, message] of Object.entries(err.fieldErrors)) {
      const control = this.form.get(field);
      if (control) {
        control.setErrors({ server: message });
        control.markAsTouched();
        mapped = true;
      }
    }
    if (!mapped || err.status !== 400) {
      this.submitError.set(err.detail);
    }
  }

  private suggestCommands(type: ApplicationType | null): void {
    if (!type) return;
    const { buildCommand, startCommand } = this.form.controls;
    if (!buildCommand.dirty) buildCommand.setValue(DEFAULT_COMMANDS[type].build);
    if (!startCommand.dirty) startCommand.setValue(DEFAULT_COMMANDS[type].start);
  }
}
