import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { of, throwError } from 'rxjs';

import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { ProjectFormComponent } from './project-form.component';

describe('ProjectFormComponent', () => {
  const api = { create: vi.fn(), update: vi.fn(), get: vi.fn() };

  async function setup() {
    api.create.mockReset();
    await TestBed.configureTestingModule({
      imports: [ProjectFormComponent],
      providers: [provideRouter([]), MessageService, { provide: ProjectApiService, useValue: api }],
    }).compileComponents();
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(ProjectFormComponent);
    await fixture.whenStable();
    return fixture;
  }

  function fill(el: HTMLElement, id: string, value: string) {
    const input = el.querySelector<HTMLInputElement>(`#${id}`)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('shows accessible errors and does not call the API when submitted empty', async () => {
    const fixture = await setup();
    const el: HTMLElement = fixture.nativeElement;
    el.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(api.create).not.toHaveBeenCalled();
    const alerts = Array.from(el.querySelectorAll('[role="alert"]')).map((a) => a.textContent?.trim());
    expect(alerts).toContain('This field is required');
    expect(el.querySelector('#name')!.getAttribute('aria-invalid')).toBe('true');
  });

  it('flags an unsafe repository URL as the user types', async () => {
    const fixture = await setup();
    const el: HTMLElement = fixture.nativeElement;
    fill(el, 'repositoryUrl', 'https://169.254.169.254/latest/meta-data');
    await fixture.whenStable();
    expect(el.textContent).toContain('Host must be one of');
  });

  it('puts server-side field errors on the matching control', async () => {
    const fixture = await setup();
    const component = fixture.componentInstance as unknown as { form: ProjectFormComponent['form'] };
    component.form.setValue({
      name: 'taken-name',
      repositoryUrl: 'https://github.com/acme/app',
      branch: 'main',
      appType: 'NODE_JS',
      buildCommand: '',
      startCommand: 'node server.js',
    });
    api.create.mockReturnValue(
      throwError(() => ({ status: 400, title: 'Validation failed', detail: 'x', fieldErrors: { name: 'already used' } })),
    );

    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ name: 'taken-name', buildCommand: null }));
    expect(fixture.nativeElement.textContent).toContain('already used');
  });

  it('submits a valid form', async () => {
    const fixture = await setup();
    const el: HTMLElement = fixture.nativeElement;
    const component = fixture.componentInstance as unknown as { form: ProjectFormComponent['form'] };
    component.form.patchValue({ appType: 'NODE_JS' });
    fill(el, 'name', 'my-api');
    fill(el, 'repositoryUrl', 'https://github.com/acme/my-api');
    api.create.mockReturnValue(of({ id: 9, name: 'my-api' }));

    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(api.create).toHaveBeenCalledTimes(1);
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/app/projects', 9]);
    expect(api.create.mock.calls[0][0].startCommand).toBe('node server.js'); // suggested from app type
  });
});
