import { TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';
import { of } from 'rxjs';

import { EnvRequirements } from '../../../core/models/api.models';
import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { ProjectEnvRequirementsComponent } from './project-env-requirements.component';

const needs = (over: Partial<EnvRequirements> = {}): EnvRequirements => ({
  items: [
    { name: 'PORT', kind: 'PLATFORM', status: 'PROVIDED', required: false, suggestedValue: null, source: '.env.example', help: 'DeployHub sets this itself.' },
    { name: 'JWT_SECRET', kind: 'SECRET', status: 'MISSING', required: true, suggestedValue: null, source: '.env.example', help: 'Any strong random value works' },
    { name: 'JWT_EXPIRES_IN', kind: 'SETTING', status: 'MISSING', required: true, suggestedValue: '24h', source: '.env.example', help: 'Fill in uses 24h' },
    { name: 'SMTP_PASSWORD', kind: 'EXTERNAL', status: 'MISSING', required: true, suggestedValue: null, source: 'src/mail.js', help: 'A key from another service' },
    { name: 'DB_HOST', kind: 'DATABASE', status: 'SET', required: true, suggestedValue: null, source: '.env.example', help: '' },
  ],
  databaseSuggested: false,
  databasePresent: true,
  missingRequired: 3,
  autoFillable: ['JWT_SECRET', 'JWT_EXPIRES_IN'],
  notes: ['Found backend/.env.example.'],
  ...over,
});

describe('ProjectEnvRequirementsComponent ("What this app needs")', () => {
  const api = { envRequirements: vi.fn(), fillEnvRequirements: vi.fn() };

  async function render(data: EnvRequirements) {
    api.envRequirements.mockReset().mockReturnValue(of(data));
    api.fillEnvRequirements.mockReset();
    await TestBed.configureTestingModule({
      imports: [ProjectEnvRequirementsComponent],
      providers: [MessageService, { provide: ProjectApiService, useValue: api }],
    }).compileComponents();
    const fixture = TestBed.createComponent(ProjectEnvRequirementsComponent);
    fixture.componentRef.setInput('projectId', 7);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('lists what is missing, with a summary, and hides what DeployHub sets itself', async () => {
    const fixture = await render(needs());
    const text = (fixture.nativeElement as HTMLElement).textContent!;
    expect(text).toContain('1 of 4 set');
    expect(text).toContain('3 required missing');
    expect(text).toContain('JWT_SECRET');
    expect(text).toContain('key from another service');
    expect(text).not.toContain('DeployHub sets this itself');
    expect(text).toContain('Fill in automatically (2)');
  });

  it('Fill in automatically fills the fillable ones and tells the page', async () => {
    const fixture = await render(needs());
    const changed = vi.fn();
    fixture.componentInstance.changed.subscribe(changed);
    api.fillEnvRequirements.mockReturnValue(of(needs({ autoFillable: [], missingRequired: 1 })));

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.fill button')!.click();

    expect(api.fillEnvRequirements).toHaveBeenCalledWith(7, ['JWT_SECRET', 'JWT_EXPIRES_IN']);
    expect(changed).toHaveBeenCalled();
  });

  it('points to the database card when the app needs a database', async () => {
    const fixture = await render(needs({ databaseSuggested: true, databasePresent: false }));
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Add MySQL database');
  });

  it('says when everything is set', async () => {
    const fixture = await render(needs({
      items: [{ name: 'JWT_SECRET', kind: 'SECRET', status: 'SET', required: true, suggestedValue: null, source: 'x', help: '' }],
      missingRequired: 0, autoFillable: [],
    }));
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Everything the app needs is set');
  });
});
