import { TestBed } from '@angular/core/testing';
import { ConfirmationService, MessageService } from 'primeng/api';
import { of } from 'rxjs';

import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { ProjectEnvVarsComponent } from './project-env-vars.component';

describe('ProjectEnvVarsComponent', () => {
  async function render(api: Record<string, unknown>) {
    await TestBed.configureTestingModule({
      imports: [ProjectEnvVarsComponent],
      providers: [MessageService, ConfirmationService, { provide: ProjectApiService, useValue: api }],
    }).compileComponents();
    const fixture = TestBed.createComponent(ProjectEnvVarsComponent);
    fixture.componentRef.setInput('projectId', 4);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('lists names but never values', async () => {
    const fixture = await render({
      listEnv: () => of([{ name: 'DB_PASSWORD', createdAt: '2026-10-26T10:00:00Z', updatedAt: '2026-10-26T10:00:00Z' }]),
    });
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('DB_PASSWORD');
    expect(text).toContain('••••••••');
    expect(text).toContain('never shown again');
  });

  it('sends a new variable once and reloads the list', async () => {
    const setEnv = vi.fn(() => of({ name: 'DB_HOST', createdAt: '', updatedAt: '' }));
    const listEnv = vi.fn(() => of([]));
    const fixture = await render({ listEnv, setEnv });
    const component = fixture.componentInstance as unknown as { form: ProjectEnvVarsComponent['form']; save(): void };

    component.form.setValue({ name: 'DB_HOST', value: 'host.docker.internal' });
    component.save();

    expect(setEnv).toHaveBeenCalledWith(4, 'DB_HOST', 'host.docker.internal');
    expect(listEnv).toHaveBeenCalledTimes(2);
    expect(component.form.getRawValue().value).toBe('');
  });

  it('rejects names the server would reject, without calling it', async () => {
    const setEnv = vi.fn();
    const fixture = await render({ listEnv: () => of([]), setEnv });
    const component = fixture.componentInstance as unknown as { form: ProjectEnvVarsComponent['form']; save(): void };

    component.form.setValue({ name: '1BAD-NAME', value: 'x' });
    component.save();

    expect(setEnv).not.toHaveBeenCalled();
  });
});
