import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { switchMap } from 'rxjs';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';

import { PageStateComponent } from '../../../core/components/shared/page-state.component';
import { ApiError, ApplicationType, DetectResult, Project, ProjectRequest } from '../../../core/models/api.models';
import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { APP_TYPE_OPTIONS, appTypeLabel } from '../../../core/utils/deployment-status';
import {
  APP_FOLDER_PATTERN,
  BRANCH_PATTERN,
  PROJECT_NAME_PATTERN,
  normaliseAppFolder,
  repositoryUrlValidator,
  trimmedLength,
  trimmedPattern,
} from './project-validators';

/**
 * What each application type normally needs. Picking a type fills these in (unless you wrote your own commands).
 * Start commands must listen on 0.0.0.0 and the port in $PORT.
 */
export const DEFAULT_COMMANDS: Record<ApplicationType, { build: string; start: string }> = {
  JAVA_SPRING_BOOT: { build: './mvnw -B -DskipTests package', start: 'java -jar target/*.jar' },
  // npm install also works without a package-lock.json (npm ci needs one).
  NODE_JS: { build: 'npm install', start: 'npm start' },
  // Serves the first build folder that exists: Angular (dist/<app>/browser), Vite/Vue (dist), React (build), Next export (out).
  STATIC_SITE: {
    build: 'npm install && npm run build',
    start: 'npx --yes serve -s "$(for d in dist/*/browser dist build out; do [ -d "$d" ] && echo "$d" && break; done)" -l $PORT',
  },
  PYTHON: { build: 'pip install --no-cache-dir -r requirements.txt', start: 'gunicorn -b 0.0.0.0:$PORT app:app' },
  DOCKERFILE: { build: '', start: '' },
};

/** Every suggested command, old ones included: a box holding one of these hasn't been customised by the user. */
const SUGGESTED = new Set<string>([
  ...Object.values(DEFAULT_COMMANDS).flatMap((c) => [c.build, c.start]),
  'npm ci',
  'npm ci && npm run build',
  'npx --yes serve -s dist -l $PORT',
]);

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
    name: ['', [Validators.required, trimmedLength(3, 50), trimmedPattern(PROJECT_NAME_PATTERN)]],
    repositoryUrl: ['', [Validators.required, Validators.maxLength(300), repositoryUrlValidator]],
    branch: ['main', [Validators.required, trimmedLength(1, 100), trimmedPattern(BRANCH_PATTERN)]],
    appType: this.fb.control<ApplicationType | null>(null, Validators.required),
    rootDirectory: ['', [Validators.maxLength(200), Validators.pattern(APP_FOLDER_PATTERN)]],
    isPrivate: [false],
    repositoryToken: ['', [Validators.maxLength(500), Validators.pattern(/^\s*[A-Za-z0-9_.~+/=:-]*\s*$/)]],
    buildCommand: ['', [Validators.maxLength(500), Validators.pattern(SINGLE_LINE)]],
    startCommand: ['', [Validators.required, Validators.maxLength(500), Validators.pattern(SINGLE_LINE)]],
    appPort: this.fb.control<number | null>(null, [Validators.min(1), Validators.max(65535)]),
    healthCheckPath: ['', [trimmedLength(0, 200), trimmedPattern(HEALTH_PATH)]],
  });

  /** Placeholder for the port field: the selected type's default. */
  protected readonly defaultPort = signal<number | null>(null);
  protected readonly isDockerfile = signal(false);
  /** Set when a type was picked but the user's own commands were kept; offers the type's suggested commands. */
  protected readonly keptCustomCommands = signal<ApplicationType | null>(null);
  protected readonly appTypeLabel = appTypeLabel;
  /** True while a loaded project or a detection result is put into the form (that isn't the user picking a type). */
  private fillingForm = false;
  /** The repository URL the project had when the Edit page opened. */
  private loadedRepositoryUrl = '';

  /** Edit page: a new repository URL without a new token drops the saved token (it belongs to the old repository). */
  protected urlChangeDropsToken(): boolean {
    const { repositoryUrl, repositoryToken } = this.form.controls;
    return !!this.savedToken() && repositoryUrl.value.trim() !== this.loadedRepositoryUrl && !repositoryToken.value.trim();
  }

  /** When the saved access token was set (Edit page), or null when there is none. */
  protected readonly savedToken = signal<string | null>(null);
  protected readonly removingToken = signal(false);
  /** What the last repository check found; shown under App folder. */
  protected readonly detection = signal<DetectResult | null>(null);
  protected readonly detecting = signal(false);
  protected readonly detectError = signal<string | null>(null);
  /** Commands filled in by the last detection: still counted as "not written by the user". */
  private detectedCommands = new Set<string>();
  /** The repository/branch/folder last checked automatically, so leaving a box unchanged doesn't check again. */
  private lastAutoDetect = '';

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
    // New and Edit alike: picking a type puts in that type's commands.
    this.form.controls.appType.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((type) => {
        if (!this.fillingForm) this.suggestCommands(type);
      });
    if (this.isEdit()) {
      this.loadProject();
    }
  }

  protected loadProject(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.projectApi.get(Number(this.id())).subscribe({
      next: (p: Project) => {
        this.fillingForm = true;
        this.form.setValue({
          name: p.name,
          repositoryUrl: p.repositoryUrl,
          branch: p.branch,
          appType: p.appType,
          rootDirectory: p.rootDirectory ?? '',
          isPrivate: p.privateRepository,
          repositoryToken: '',
          buildCommand: p.buildCommand ?? '',
          startCommand: p.startCommand ?? '',
          appPort: p.appPort,
          healthCheckPath: p.healthCheckPath ?? '',
        });
        this.fillingForm = false;
        this.savedToken.set(p.privateRepository ? p.repositoryTokenUpdatedAt : null);
        this.loadedRepositoryUrl = p.repositoryUrl;
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.loadError.set(err);
        this.loading.set(false);
      },
    });
  }

  /** On leaving a text box: drop spaces at the start and end, so what you see is what is saved. */
  protected trim(field: keyof typeof this.form.controls): void {
    const control = this.form.controls[field];
    if (typeof control.value === 'string' && control.value !== control.value.trim()) {
      control.setValue(control.value.trim() as never);
    }
  }

  protected showError(field: keyof typeof this.form.controls): boolean {
    const control = this.form.controls[field];
    return control.invalid && (control.touched || control.dirty);
  }

  protected errorText(field: keyof typeof this.form.controls): string {
    const errors = this.form.controls[field].errors ?? {};
    if (errors['server']) return errors['server'];
    if (errors['tokenMissing']) return 'Paste the access token, or untick "This repository is private"';
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
        case 'rootDirectory':
          return 'Must be a folder inside the repository, e.g. backend or apps/web';
        case 'repositoryToken':
          return 'That is not a valid access token (copy it again, without spaces)';
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
    // "Private" ticked but no token and none saved: the project would be saved as public (review #9).
    if (raw.isPrivate && !raw.repositoryToken.trim() && !this.savedToken()) {
      this.form.controls.repositoryToken.setErrors({ tokenMissing: true });
      this.form.controls.repositoryToken.markAsTouched();
      return;
    }
    // Unticking "private" on the Edit page means: forget the saved token (review #2).
    const removeSavedToken = this.isEdit() && !!this.savedToken() && !raw.isPrivate;
    const { isPrivate: _isPrivate, ...fields } = raw;
    const request: ProjectRequest = {
      ...fields,
      appType: raw.appType!,
      name: raw.name.trim(),
      branch: raw.branch.trim(),
      repositoryUrl: raw.repositoryUrl.trim(),
      rootDirectory: normaliseAppFolder(raw.rootDirectory),
      repositoryToken: raw.isPrivate ? raw.repositoryToken.trim() || null : null,
      buildCommand: raw.buildCommand.trim() || null,
      startCommand: raw.startCommand.trim() || null,
      appPort: raw.appPort ?? null,
      healthCheckPath: raw.healthCheckPath.trim() || null,
    };
    this.saving.set(true);
    const saved$ = this.isEdit()
      ? this.projectApi.update(Number(this.id()), request)
      : this.projectApi.create(request);
    const call$ = removeSavedToken
      ? saved$.pipe(switchMap((project) => this.projectApi.removeRepositoryToken(project.id)))
      : saved$;
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

  /** New project: check the repository by itself once the URL (and branch, App folder) are filled in. */
  protected autoDetect(): void {
    if (this.isEdit()) return;
    const { repositoryUrl, branch, rootDirectory } = this.form.controls;
    if (repositoryUrl.invalid || !repositoryUrl.value.trim() || branch.invalid || rootDirectory.invalid) return;
    const key = [repositoryUrl.value.trim(), branch.value.trim(), normaliseAppFolder(rootDirectory.value),
      this.typedToken() ?? ''].join('|');
    if (key === this.lastAutoDetect) return;
    this.lastAutoDetect = key;
    this.detect(false);
  }

  /**
   * Asks the server to look at the repository and fills in the type and commands it suggests. From the button
   * (force = true) it always fills them in; automatically it keeps commands the user wrote.
   */
  protected detect(force = true): void {
    const { repositoryUrl, branch, rootDirectory } = this.form.controls;
    if (repositoryUrl.invalid || !repositoryUrl.value.trim() || branch.invalid || rootDirectory.invalid) {
      repositoryUrl.markAsTouched();
      this.detectError.set('Enter the repository URL (and branch) first.');
      return;
    }
    this.detecting.set(true);
    this.detectError.set(null);
    this.projectApi
      .detect(repositoryUrl.value.trim(), branch.value.trim(), normaliseAppFolder(rootDirectory.value),
        this.typedToken(), this.isEdit() ? Number(this.id()) : null)
      .subscribe({
        next: (result) => {
          this.detecting.set(false);
          this.detection.set(result);
          if (result.notes.some((n) => n.includes("isn't public"))) this.form.controls.isPrivate.setValue(true);
          if (result.appType) this.applyDetection(result, force);
        },
        error: (err: ApiError) => {
          this.detecting.set(false);
          this.detectError.set(err.detail);
        },
      });
  }

  /** The token typed into the form for a private repository (not the saved one, which the server uses itself). */
  private typedToken(): string | null {
    const { isPrivate, repositoryToken } = this.form.controls;
    return isPrivate.value && repositoryToken.valid ? repositoryToken.value.trim() || null : null;
  }

  /** The repository looked private to detection: open the token box so the user sees what to do. */
  protected readonly looksPrivate = computed(() =>
    (this.detection()?.notes ?? []).some((n) => n.includes("isn't public") || n.includes('access token')),
  );

  /** Edit page: forget the saved access token. */
  protected removeToken(): void {
    this.removingToken.set(true);
    this.projectApi.removeRepositoryToken(Number(this.id())).subscribe({
      next: () => {
        this.removingToken.set(false);
        this.savedToken.set(null);
        this.form.controls.isPrivate.setValue(false);
        this.messages.add({ severity: 'success', summary: 'Access token removed', detail: 'The repository is read as public now.' });
      },
      error: (err: ApiError) => {
        this.removingToken.set(false);
        this.messages.add({ severity: 'error', summary: err.title, detail: err.detail });
      },
    });
  }

  /** "These folders look like apps": choosing one sets App folder and checks it. */
  protected chooseAppFolder(folder: string): void {
    this.form.controls.rootDirectory.setValue(folder);
    this.form.controls.rootDirectory.markAsDirty();
    this.detect(true);
  }

  private applyDetection(result: DetectResult, force: boolean): void {
    const { appType, buildCommand, startCommand } = this.form.controls;
    const untouched = (value: string) =>
      !value.trim() || SUGGESTED.has(value.trim()) || this.detectedCommands.has(value.trim());
    this.fillingForm = true; // the type changes below; don't put in that type's generic commands
    appType.setValue(result.appType);
    this.fillingForm = false;
    if (force || (untouched(buildCommand.value) && untouched(startCommand.value))) {
      buildCommand.setValue(result.buildCommand ?? '');
      startCommand.setValue(result.startCommand ?? '');
      buildCommand.markAsDirty();
      startCommand.markAsDirty();
      this.detectedCommands = new Set([result.buildCommand ?? '', result.startCommand ?? ''].filter((c) => c));
      this.keptCustomCommands.set(null);
    } else {
      this.keptCustomCommands.set(result.appType);
    }
  }

  /**
   * Puts in the type's commands when the boxes are empty or still hold suggested commands (of any type). Commands
   * the user wrote are kept, and keptCustomCommands offers the suggestion instead.
   */
  private suggestCommands(type: ApplicationType | null): void {
    if (!type) return;
    const { buildCommand, startCommand } = this.form.controls;
    const untouched = (value: string) =>
      !value.trim() || SUGGESTED.has(value.trim()) || this.detectedCommands.has(value.trim());
    if (untouched(buildCommand.value) && untouched(startCommand.value)) {
      this.useSuggestedCommands(type);
    } else {
      this.keptCustomCommands.set(type);
    }
  }

  protected useSuggestedCommands(type: ApplicationType): void {
    const { buildCommand, startCommand } = this.form.controls;
    buildCommand.setValue(DEFAULT_COMMANDS[type].build);
    startCommand.setValue(DEFAULT_COMMANDS[type].start);
    buildCommand.markAsDirty();
    startCommand.markAsDirty();
    this.keptCustomCommands.set(null);
  }
}
