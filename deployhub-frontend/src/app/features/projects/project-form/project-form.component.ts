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

/** Suggestions for a new project. Start commands must listen on 0.0.0.0 and the port in $PORT. */
const DEFAULT_COMMANDS: Record<ApplicationType, { build: string; start: string }> = {
  JAVA_SPRING_BOOT: { build: './mvnw -B -DskipTests package', start: 'java -jar target/*.jar' },
  NODE_JS: { build: 'npm ci', start: 'npm start' },
  STATIC_SITE: { build: 'npm ci && npm run build', start: 'npx --yes serve -s dist -l $PORT' },
  PYTHON: { build: 'pip install --no-cache-dir -r requirements.txt', start: 'gunicorn -b 0.0.0.0:$PORT app:app' },
  DOCKERFILE: { build: '', start: '' },
};

/** Must match ApplicationType.defaultPort() in the backend. */
export const DEFAULT_PORTS: Record<ApplicationType, number> = {
  JAVA_SPRING_BOOT: 8080,
  NODE_JS: 3000,
  STATIC_SITE: 3000,
  PYTHON: 8000,
  DOCKERFILE: 8080,
};

/** Commands go into a generated Dockerfile, where a line break would start a new instruction. */
const SINGLE_LINE = /^[^\r\n]*$/;
const HEALTH_PATH = /^\/[A-Za-z0-9._~/?=&%-]*$/;

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
    buildCommand: ['', [Validators.maxLength(500), Validators.pattern(SINGLE_LINE)]],
    startCommand: ['', [Validators.required, Validators.maxLength(500), Validators.pattern(SINGLE_LINE)]],
    appPort: this.fb.control<number | null>(null, [Validators.min(1), Validators.max(65535)]),
    healthCheckPath: ['', [Validators.maxLength(200), Validators.pattern(HEALTH_PATH)]],
  });

  /** Placeholder for the port field: the selected type's default. */
  protected readonly defaultPort = signal<number | null>(null);
  protected readonly isDockerfile = signal(false);

  ngOnInit(): void {
    // A Dockerfile project may leave the start command empty (the image's own CMD is used).
    this.form.controls.appType.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((type) => {
      const start = this.form.controls.startCommand;
      const dockerfile = type === 'DOCKERFILE';
      this.isDockerfile.set(dockerfile);
      this.defaultPort.set(type ? DEFAULT_PORTS[type] : null);
      start.setValidators(
        dockerfile
          ? [Validators.maxLength(500), Validators.pattern(SINGLE_LINE)]
          : [Validators.required, Validators.maxLength(500), Validators.pattern(SINGLE_LINE)],
      );
      start.updateValueAndValidity({ emitEvent: false });
    });
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
          startCommand: p.startCommand ?? '',
          appPort: p.appPort,
          healthCheckPath: p.healthCheckPath ?? '',
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
    if (errors['min'] || errors['max']) return 'Must be a port between 1 and 65535';
    if (errors['repositoryUrl']) return errors['repositoryUrl'];
    if (errors['pattern']) {
      switch (field) {
        case 'name':
          return 'Use lowercase letters, digits and hyphens (no leading/trailing hyphen)';
        case 'branch':
          return 'Not a valid branch name';
        case 'healthCheckPath':
          return 'Must start with / (for example /health)';
        default:
          return 'Must be a single line';
      }
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
      startCommand: raw.startCommand.trim() || null,
      appPort: raw.appPort ?? null,
      healthCheckPath: raw.healthCheckPath.trim() || null,
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
