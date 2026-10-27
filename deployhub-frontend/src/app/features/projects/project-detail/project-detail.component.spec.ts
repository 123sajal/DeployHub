import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { NEVER, of, throwError } from 'rxjs';

import { BillingApiService } from '../../../core/services/api/billing-api.service';
import { DeploymentApiService } from '../../../core/services/api/deployment-api.service';
import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { ProjectDetailComponent } from './project-detail.component';

describe('ProjectDetailComponent deploy button', () => {
  const create = vi.fn();

  async function render() {
    create.mockReset();
    await TestBed.configureTestingModule({
      imports: [ProjectDetailComponent],
      providers: [
        provideRouter([]),
        MessageService,
        ConfirmationService,
        { provide: ProjectApiService, useValue: { get: () => NEVER } },
        { provide: DeploymentApiService, useValue: { create, list: () => NEVER } },
        { provide: BillingApiService, useValue: { summary: () => NEVER } },
      ],
    }).compileComponents();
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(ProjectDetailComponent);
    fixture.componentRef.setInput('id', '4');
    return fixture.componentInstance as unknown as { deploy(): void };
  }

  it('sends one request with a unique idempotency key, even when clicked twice quickly', async () => {
    const component = await render();
    create.mockReturnValue(NEVER); // first request still in flight

    component.deploy();
    component.deploy();

    expect(create).toHaveBeenCalledTimes(1);
    const [projectId, key] = create.mock.calls[0];
    expect(projectId).toBe(4);
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('uses a new key for each separate deploy', async () => {
    const component = await render();
    create.mockReturnValue(of({ id: 1 }));
    component.deploy();
    create.mockReturnValue(of({ id: 2 }));
    (component as unknown as { deploying: { set(v: boolean): void } }).deploying.set(false);
    component.deploy();

    expect(create.mock.calls[0][1]).not.toBe(create.mock.calls[1][1]);
  });
});

describe('ProjectDetailComponent check before deploying', () => {
  const create = vi.fn();
  const fill = vi.fn();
  const item = (name: string, kind: string, status = 'MISSING') =>
    ({ name, kind, status, required: true, suggestedValue: null, source: '.env.example', help: '' }) as never;

  async function render() {
    [create, fill].forEach((f) => f.mockReset());
    await TestBed.configureTestingModule({
      imports: [ProjectDetailComponent],
      providers: [
        provideRouter([]),
        MessageService,
        ConfirmationService,
        { provide: ProjectApiService, useValue: { get: () => NEVER, fillEnvRequirements: fill } },
        { provide: DeploymentApiService, useValue: { create, list: () => NEVER } },
        { provide: BillingApiService, useValue: { summary: () => NEVER } },
      ],
    }).compileComponents();
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(ProjectDetailComponent);
    fixture.componentRef.setInput('id', '4');
    const c = fixture.componentInstance as unknown as { deploy(): void; requirements: { set(v: unknown): void } };
    return { c, confirm: vi.spyOn(TestBed.inject(ConfirmationService), 'confirm') };
  }

  it('offers to fill in missing settings, then deploys', async () => {
    const { c, confirm } = await render();
    c.requirements.set({ items: [item('JWT_SECRET', 'SECRET')], autoFillable: ['JWT_SECRET'], missingRequired: 1, notes: [] });
    fill.mockReturnValue(of({ items: [item('JWT_SECRET', 'SECRET', 'SET')], autoFillable: [], missingRequired: 0, notes: [] }));
    create.mockReturnValue(of({ id: 12 }));
    confirm.mockImplementation((cfg) => {
      expect(cfg.acceptLabel).toBe('Fill in & deploy');
      cfg.accept!();
      return TestBed.inject(ConfirmationService);
    });

    c.deploy();

    expect(fill).toHaveBeenCalledWith(4, ['JWT_SECRET']);
    expect(create).toHaveBeenCalledTimes(1);
  });

  it('says what only the user can add, and can deploy anyway', async () => {
    const { c, confirm } = await render();
    c.requirements.set({ items: [item('SMTP_PASSWORD', 'EXTERNAL'), item('DB_HOST', 'DATABASE')], autoFillable: [], missingRequired: 2, notes: [] });
    create.mockReturnValue(of({ id: 13 }));
    confirm.mockImplementation((cfg) => {
      expect(cfg.message).toContain('SMTP_PASSWORD');
      expect(cfg.message).toContain('Add MySQL database');
      expect(cfg.acceptLabel).toBe('Deploy anyway');
      cfg.accept!();
      return TestBed.inject(ConfirmationService);
    });

    c.deploy();

    expect(fill).not.toHaveBeenCalled();
    expect(create).toHaveBeenCalledTimes(1);
  });

  it('deploys straight away when nothing required is missing', async () => {
    const { c, confirm } = await render();
    c.requirements.set({ items: [item('JWT_SECRET', 'SECRET', 'SET')], autoFillable: [], missingRequired: 0, notes: [] });
    create.mockReturnValue(of({ id: 14 }));

    c.deploy();

    expect(confirm).not.toHaveBeenCalled();
    expect(create).toHaveBeenCalledTimes(1);
  });
});

describe('ProjectDetailComponent when a deploy needs payment', () => {
  it('opens the payment page for this project on 402', async () => {
    const create = vi.fn().mockReturnValue(
      throwError(() => ({ status: 402, title: 'Payment required', detail: 'Your free project is used.', fieldErrors: {} })),
    );
    await TestBed.configureTestingModule({
      imports: [ProjectDetailComponent],
      providers: [
        provideRouter([]),
        MessageService,
        ConfirmationService,
        { provide: ProjectApiService, useValue: { get: () => NEVER } },
        { provide: DeploymentApiService, useValue: { create, list: () => NEVER } },
        { provide: BillingApiService, useValue: { summary: () => NEVER } },
      ],
    }).compileComponents();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(ProjectDetailComponent);
    fixture.componentRef.setInput('id', '305');

    (fixture.componentInstance as unknown as { deploy(): void }).deploy();

    expect(navigate).toHaveBeenCalledWith(['/app/projects/payment'], { queryParams: { project: '305' } });
  });
});
