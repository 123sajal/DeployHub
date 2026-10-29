import { TestBed } from '@angular/core/testing';
import { ConfirmationService, MessageService } from 'primeng/api';
import { of, throwError } from 'rxjs';

import { ProjectDatabase } from '../../../core/models/api.models';
import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { ProjectDatabaseComponent } from './project-database.component';

const DB: ProjectDatabase = {
  engine: 'MySQL',
  databaseName: 'p4_shop',
  username: 'p4_shop',
  appHost: 'deployhub-mysql',
  appPort: 3306,
  localHost: '127.0.0.1',
  localPort: 3307,
  password: null,
  createdAt: '2026-10-26T10:00:00Z',
  environmentVariables: ['DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD', 'DATABASE_URL'],
};

describe('ProjectDatabaseComponent', () => {
  async function render(api: Record<string, unknown>) {
    await TestBed.configureTestingModule({
      imports: [ProjectDatabaseComponent],
      providers: [
        MessageService,
        ConfirmationService,
        { provide: ProjectApiService, useValue: { databaseExportUrl: () => '/api/projects/4/database/export', ...api } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ProjectDatabaseComponent);
    fixture.componentRef.setInput('projectId', 4);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('offers one click when the project has no database', async () => {
    const fixture = await render({ getDatabase: () => of(null) });
    expect(fixture.nativeElement.textContent).toContain('Add MySQL database');
  });

  it('creates it, shows the password once and tells the page the variables changed', async () => {
    const createDatabase = vi.fn(() => of({ ...DB, password: 'Abc123Def456Ghi789Jkl012' }));
    const fixture = await render({ getDatabase: () => of(null), createDatabase });
    let changed = 0;
    fixture.componentInstance.changed.subscribe(() => changed++);

    (fixture.componentInstance as unknown as { create(): void }).create();
    fixture.detectChanges();

    expect(createDatabase).toHaveBeenCalledWith(4);
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Abc123Def456Ghi789Jkl012');
    expect(text).toContain('shown only now');
    expect(text).toContain('deployhub-mysql:3306');
    expect(text).toContain('127.0.0.1:3307');
    expect(changed).toBe(1);
  });

  it('shows an existing database without any password', async () => {
    const fixture = await render({ getDatabase: () => of(DB) });
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('p4_shop');
    expect(text).not.toContain('shown only now');
    expect(text).toContain('Remove database');
  });

  describe('your data (import / export)', () => {
    const sql = new File(['CREATE TABLE a (id INT);'], 'mydb.sql', { type: 'application/sql' });

    function choose(fixture: Awaited<ReturnType<typeof render>>, file: File) {
      (fixture.componentInstance as unknown as { pick(f: unknown): void }).pick([file]);
      fixture.detectChanges();
    }

    it('imports the chosen file, with "delete the current data first" when ticked, and shows the result', async () => {
      const importDatabase = vi.fn(() => of({ tablesBefore: 0, tablesAfter: 3, message: 'Imported: the database now has 3 tables.' }));
      const fixture = await render({ getDatabase: () => of(DB), importDatabase, databaseExportUrl: () => '/api/projects/4/database/export' });
      const el: HTMLElement = fixture.nativeElement;
      expect(el.textContent).toContain('mysqldump -u root -p mydb');
      choose(fixture, sql);
      el.querySelector<HTMLInputElement>('.check input')!.click();
      fixture.detectChanges();
      expect(el.textContent).toContain('Everything now in the database is deleted');

      [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Import data'))!.click();
      fixture.detectChanges();

      expect(importDatabase).toHaveBeenCalledWith(4, sql, true);
      expect(el.textContent).toContain('now has 3 tables');
    });

    it("shows MySQL's explanation when the file can't be imported", async () => {
      const importDatabase = vi.fn(() => throwError(() => ({
        status: 400, title: 'Validation failed', detail: 'x',
        fieldErrors: { file: 'MySQL couldn\'t run the file: ERROR 1064 at line 1: You have an error in your SQL syntax' },
      })));
      const fixture = await render({ getDatabase: () => of(DB), importDatabase, databaseExportUrl: () => '/x' });
      choose(fixture, sql);

      [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.includes('Import data'))!.click();
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain('You have an error in your SQL syntax');
    });

    it('offers the export as a download link', async () => {
      const fixture = await render({ getDatabase: () => of(DB), databaseExportUrl: (id: number) => `/api/projects/${id}/database/export` });
      const link = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a.export')!;
      expect(link.getAttribute('href')).toBe('/api/projects/4/database/export');
      expect(link.hasAttribute('download')).toBe(true);
    });
  });
});
