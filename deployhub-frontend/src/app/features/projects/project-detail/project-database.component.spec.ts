import { TestBed } from '@angular/core/testing';
import { ConfirmationService, MessageService } from 'primeng/api';
import { of } from 'rxjs';

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
      providers: [MessageService, ConfirmationService, { provide: ProjectApiService, useValue: api }],
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
});
