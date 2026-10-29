import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';

import { AssistantHintComponent } from '../../../core/components/assistant/assistant-hint.component';
import { ApiError, ProjectDatabase } from '../../../core/models/api.models';
import { ProjectApiService } from '../../../core/services/api/project-api.service';

/**
 * One-click database: a MySQL database for this project on DeployHub's shared server. Its connection details
 * become environment variables; the password is shown once (after creating it or resetting it).
 */
@Component({
  selector: 'app-project-database',
  imports: [DatePipe, ButtonModule, MessageModule, AssistantHintComponent],
  template: `
    <h2 class="dh-card-title">Database</h2>

    @if (error(); as err) {
      <p class="dh-error" role="alert">{{ err.detail }}</p>
    }

    @if (loading()) {
      <p class="dh-muted">Loading…</p>
    } @else if (database(); as db) {
      @if (shownPassword(); as password) {
        <p-message severity="warn" styleClass="once">
          <div>
            <strong>Password, shown only now:</strong>
            <code class="secret">{{ password }}</code>
            <p-button icon="pi pi-copy" [text]="true" size="small" ariaLabel="Copy password" (onClick)="copy(password)" />
            <br />
            It is already in the app's environment variables. Keep a copy only if you want to connect yourself.
          </div>
        </p-message>
      }
      <dl class="dh-dl">
        <dt>Engine</dt>
        <dd>{{ db.engine }}</dd>
        <dt>Database / user</dt>
        <dd class="mono">{{ db.databaseName }}</dd>
        <dt>From your app</dt>
        <dd class="mono">{{ db.appHost }}:{{ db.appPort }}</dd>
        <dt>From this computer</dt>
        <dd class="mono">{{ db.localHost }}:{{ db.localPort }}</dd>
        <dt>Created</dt>
        <dd>{{ db.createdAt | date: 'medium' }}</dd>
      </dl>
      <p class="dh-muted hint">
        Set for the app: <code>{{ db.environmentVariables.join(', ') }}</code>. Deploy again after adding the
        database or resetting its password.
      </p>
      <div class="data">
        <h3>Your data</h3>
        <p class="dh-muted hint">
          Bring the data from a database on your computer: export it to a <code>.sql</code> file, e.g.
          <code>mysqldump -u root -p mydb &gt; mydb.sql</code>, or Export in MySQL Workbench / phpMyAdmin (up to 50 MB).
          It goes into this project's database only.
        </p>
        <div class="import">
          <input #fileInput type="file" accept=".sql" aria-label="SQL file to import" (change)="pick(fileInput.files)" />
          <label class="check">
            <input type="checkbox" [checked]="replace()" (change)="replace.set($any($event.target).checked)" />
            Delete the current data first
          </label>
          <p-button label="Import data" icon="pi pi-upload" size="small" [disabled]="!file() || working()"
                    [loading]="importing()" (onClick)="importData(fileInput)" />
          <a class="export" [href]="exportUrl()" download>
            <i class="pi pi-download" aria-hidden="true"></i> Export data (.sql)
          </a>
        </div>
        @if (replace()) {
          <small class="dh-error">Everything now in the database is deleted before the import. Export a backup first if
            you might need it.</small>
        }
        @if (importResult(); as result) {
          <p-message severity="success" styleClass="result" [text]="result" />
        }
        @if (importError(); as message) {
          <p class="dh-error" role="alert">{{ message }}</p>
          <app-assistant-hint topic="IMPORT_REFUSED" [projectId]="projectId()" [errorText]="message" [auto]="true" />
        }
      </div>
      <div class="actions">
        <p-button label="Reset password" icon="pi pi-refresh" severity="secondary" [outlined]="true" size="small"
                  [loading]="working()" (onClick)="resetPassword()" />
        <p-button label="Remove database" icon="pi pi-trash" severity="danger" [text]="true" size="small"
                  [disabled]="working()" (onClick)="confirmRemove(db)" />
      </div>
    } @else {
      <p class="dh-muted">
        Add a MySQL database for this project in one click. DeployHub creates it on its own MySQL server and gives
        your app <code>DB_HOST</code>, <code>DB_PORT</code>, <code>DB_NAME</code>, <code>DB_USER</code>,
        <code>DB_PASSWORD</code> and <code>DATABASE_URL</code>.
      </p>
      <p-button label="Add MySQL database" icon="pi pi-database" [loading]="working()" (onClick)="create()" />
      @if (working()) {
        <p class="dh-muted hint">The first database also starts the MySQL server: this can take a minute or two.</p>
      }
    }
  `,
  styles: `
    .hint { font-size: 0.85rem; margin: 0.5rem 0; }
    .actions { display: flex; gap: 0.5rem; justify-content: space-between; flex-wrap: wrap; }
    .secret { font-size: 0.95rem; margin: 0 0.4rem; user-select: all; }
    :host ::ng-deep .once { margin-bottom: 0.75rem; }
    .data { border-top: 1px solid var(--dh-border); padding-top: 0.6rem; margin: 0.6rem 0; }
    .data h3 { margin: 0 0 0.25rem; font-size: 0.9rem; }
    .import { display: flex; align-items: center; gap: 0.5rem 0.9rem; flex-wrap: wrap; font-size: 0.85rem; }
    .import input[type='file'] { max-width: 100%; font-size: 0.82rem; }
    .check { display: inline-flex; align-items: center; gap: 0.35rem; cursor: pointer; }
    .export { margin-left: auto; font-size: 0.85rem; }
    :host ::ng-deep .result { margin-top: 0.5rem; }
  `,
})
export class ProjectDatabaseComponent implements OnInit {
  readonly projectId = input.required<number>();
  /** The project's environment variables changed (database added, password reset or database removed). */
  readonly changed = output<void>();

  private readonly api = inject(ProjectApiService);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);

  protected readonly database = signal<ProjectDatabase | null>(null);
  protected readonly shownPassword = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly working = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly file = signal<File | null>(null);
  protected readonly replace = signal(false);
  protected readonly importing = signal(false);
  protected readonly importResult = signal<string | null>(null);
  protected readonly importError = signal<string | null>(null);
  protected readonly exportUrl = computed(() => this.api.databaseExportUrl(this.projectId()));

  ngOnInit(): void {
    this.api.getDatabase(this.projectId()).subscribe({
      next: (db) => {
        this.database.set(db);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.error.set(err);
        this.loading.set(false);
      },
    });
  }

  protected create(): void {
    this.run(this.api.createDatabase(this.projectId()), 'Database created');
  }

  protected resetPassword(): void {
    this.run(this.api.resetDatabasePassword(this.projectId()), 'New database password set');
  }

  private run(request: ReturnType<ProjectApiService['createDatabase']>, summary: string): void {
    this.working.set(true);
    this.error.set(null);
    request.subscribe({
      next: (db) => {
        this.working.set(false);
        this.database.set({ ...db, password: null });
        this.shownPassword.set(db.password);
        this.messages.add({ severity: 'success', summary, detail: 'Deploy again to use it' });
        this.changed.emit();
      },
      error: (err: ApiError) => {
        this.working.set(false);
        this.error.set(err);
      },
    });
  }

  protected pick(files: FileList | null): void {
    this.file.set(files?.[0] ?? null);
    this.importResult.set(null);
    this.importError.set(null);
  }

  /** Uploads the chosen .sql file into the project's database. */
  protected importData(input: HTMLInputElement): void {
    const file = this.file();
    if (!file) return;
    this.importing.set(true);
    this.importResult.set(null);
    this.importError.set(null);
    this.api.importDatabase(this.projectId(), file, this.replace()).subscribe({
      next: (result) => {
        this.importing.set(false);
        this.importResult.set(result.message);
        this.file.set(null);
        this.replace.set(false);
        input.value = '';
      },
      error: (err: ApiError) => {
        this.importing.set(false);
        this.importError.set(err.fieldErrors['file'] ?? err.detail);
      },
    });
  }

  protected confirmRemove(db: ProjectDatabase): void {
    this.confirm.confirm({
      header: 'Remove the database?',
      message: `This deletes ${db.databaseName} with ALL its data, and its environment variables. It cannot be undone.`,
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Delete database',
      rejectLabel: 'Cancel',
      acceptButtonProps: { severity: 'danger' },
      rejectButtonProps: { severity: 'secondary', text: true },
      accept: () => {
        this.working.set(true);
        this.api.removeDatabase(this.projectId()).subscribe({
          next: () => {
            this.working.set(false);
            this.database.set(null);
            this.shownPassword.set(null);
            this.messages.add({ severity: 'success', summary: 'Database deleted' });
            this.changed.emit();
          },
          error: (err: ApiError) => {
            this.working.set(false);
            this.error.set(err);
          },
        });
      },
    });
  }

  protected async copy(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.messages.add({ severity: 'success', summary: 'Password copied' });
    } catch {
      this.messages.add({ severity: 'error', summary: 'Copy failed', detail: 'Select the password and copy it.' });
    }
  }
}
