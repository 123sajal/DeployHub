import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { of, throwError } from 'rxjs';

import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { DEFAULT_COMMANDS, ProjectFormComponent } from './project-form.component';

describe('ProjectFormComponent', () => {
  const api = { create: vi.fn(), update: vi.fn(), get: vi.fn(), detect: vi.fn(), removeRepositoryToken: vi.fn() };

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
      rootDirectory: '',
      isPrivate: false,
      repositoryToken: '',
      buildCommand: '',
      startCommand: 'node server.js',
      appPort: null,
      healthCheckPath: '',
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
    expect(api.create.mock.calls[0][0].startCommand).toBe('npm start'); // suggested from app type
    expect(api.create.mock.calls[0][0]).toEqual(
      expect.objectContaining({ appPort: null, healthCheckPath: null, rootDirectory: null, repositoryToken: null }),
    );
  });

  it('sends the app folder tidied, and no folder as null', async () => {
    const fixture = await setup();
    const el: HTMLElement = fixture.nativeElement;
    const component = fixture.componentInstance as unknown as { form: ProjectFormComponent['form'] };
    component.form.patchValue({ appType: 'NODE_JS' });
    fill(el, 'name', 'ems-api');
    fill(el, 'repositoryUrl', 'https://github.com/acme/ems');
    fill(el, 'rootDirectory', './backend/');
    api.create.mockReturnValue(of({ id: 3, name: 'ems-api' }));

    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(api.create.mock.calls[0][0].rootDirectory).toBe('backend');
  });

  it('ignores spaces typed before or after the name, and trims them when leaving the box', async () => {
    const fixture = await setup();
    const el: HTMLElement = fixture.nativeElement;
    const component = fixture.componentInstance as unknown as { form: ProjectFormComponent['form'] };
    component.form.patchValue({ appType: 'NODE_JS' });
    fill(el, 'name', 'employeemanagement ');
    expect(component.form.controls.name.valid).toBe(true);
    el.querySelector<HTMLInputElement>('#name')!.dispatchEvent(new Event('blur'));
    expect(component.form.controls.name.value).toBe('employeemanagement');

    fill(el, 'name', '   ');
    expect(component.form.controls.name.errors).toEqual({ required: true });

    fill(el, 'name', ' my-api ');
    fill(el, 'repositoryUrl', 'https://github.com/acme/my-api');
    fill(el, 'healthCheckPath', '/api/health ');
    api.create.mockReturnValue(of({ id: 5, name: 'my-api' }));
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(api.create.mock.calls[0][0]).toEqual(expect.objectContaining({ name: 'my-api', healthCheckPath: '/api/health' }));
  });

  it('flags an app folder outside the repository', async () => {
    const fixture = await setup();
    const el: HTMLElement = fixture.nativeElement;
    fill(el, 'rootDirectory', '../secrets');
    el.querySelector<HTMLInputElement>('#rootDirectory')!.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(el.querySelector('#folder-error')?.textContent).toContain('folder inside the repository');
  });

  it('lets a Dockerfile project leave the start command empty, but no other type', async () => {
    const fixture = await setup();
    const component = fixture.componentInstance as unknown as { form: ProjectFormComponent['form'] };
    const start = component.form.controls.startCommand;

    component.form.controls.appType.setValue('NODE_JS');
    start.setValue('');
    expect(start.valid).toBe(false);

    component.form.controls.appType.setValue('DOCKERFILE');
    expect(start.valid).toBe(true);
  });

  it('rejects multi-line commands, bad ports and health paths', async () => {
    const fixture = await setup();
    const component = fixture.componentInstance as unknown as { form: ProjectFormComponent['form'] };
    const { buildCommand, appPort, healthCheckPath } = component.form.controls;

    buildCommand.setValue('npm ci\nnpm test');
    appPort.setValue(70000);
    healthCheckPath.setValue('health');

    expect(buildCommand.hasError('pattern')).toBe(true);
    expect(appPort.hasError('max')).toBe(true);
    expect(healthCheckPath.hasError('pattern')).toBe(true);
  });

  describe('picking an application type', () => {
    type Form = { form: ProjectFormComponent['form']; keptCustomCommands(): string | null; useSuggestedCommands(t: string): void };

    it("puts in each type's commands, also when switching between types", async () => {
      const fixture = await setup();
      const c = fixture.componentInstance as unknown as Form;
      for (const type of ['NODE_JS', 'PYTHON', 'STATIC_SITE', 'JAVA_SPRING_BOOT', 'DOCKERFILE', 'NODE_JS'] as const) {
        c.form.controls.appType.setValue(type);
        expect(c.form.controls.buildCommand.value).toBe(DEFAULT_COMMANDS[type].build);
        expect(c.form.controls.startCommand.value).toBe(DEFAULT_COMMANDS[type].start);
      }
      expect(c.keptCustomCommands()).toBeNull();
    });

    it('keeps commands you wrote yourself and offers the suggested ones', async () => {
      const fixture = await setup();
      const el: HTMLElement = fixture.nativeElement;
      const c = fixture.componentInstance as unknown as Form;
      c.form.controls.appType.setValue('NODE_JS');
      fill(el, 'startCommand', 'npm run db:setup && npm start');

      c.form.controls.appType.setValue('PYTHON');
      fixture.detectChanges();

      expect(c.form.controls.startCommand.value).toBe('npm run db:setup && npm start');
      expect(el.textContent).toContain('Use the suggested Python commands');
      c.useSuggestedCommands('PYTHON');
      expect(c.form.controls.startCommand.value).toBe(DEFAULT_COMMANDS.PYTHON.start);
      expect(c.keptCustomCommands()).toBeNull();
    });

    it('on the Edit page: opening the project keeps its commands; picking another type suggests', async () => {
      api.get.mockReturnValue(of({
        id: 4, name: 'ems', repositoryUrl: 'https://github.com/acme/ems', branch: 'main', appType: 'NODE_JS',
        rootDirectory: 'backend', buildCommand: 'npm ci', startCommand: 'npm start', appPort: null, healthCheckPath: null,
        privateRepository: false, repositoryTokenUpdatedAt: null,
      }));
      api.create.mockReset();
      await TestBed.configureTestingModule({
        imports: [ProjectFormComponent],
        providers: [provideRouter([]), MessageService, { provide: ProjectApiService, useValue: api }],
      }).compileComponents();
      const fixture = TestBed.createComponent(ProjectFormComponent);
      fixture.componentRef.setInput('id', '4');
      await fixture.whenStable();
      const c = fixture.componentInstance as unknown as Form;
      expect(c.form.controls.buildCommand.value).toBe('npm ci');
      expect(c.form.controls.startCommand.value).toBe('npm start');

      c.form.controls.appType.setValue('STATIC_SITE');

      expect(c.form.controls.buildCommand.value).toBe(DEFAULT_COMMANDS.STATIC_SITE.build);
      expect(c.form.controls.startCommand.value).toBe(DEFAULT_COMMANDS.STATIC_SITE.start);
    });
  });

  describe('detecting from the repository', () => {
    type Form = {
      form: ProjectFormComponent['form'];
      detect(force?: boolean): void;
      autoDetect(): void;
      chooseAppFolder(f: string): void;
      detectError(): string | null;
    };
    const backend = {
      appType: 'NODE_JS', buildCommand: 'npm ci', startCommand: 'npm run db:setup && npm start', appFolders: [],
      notes: ['Runs "db:setup" before starting, so the database tables exist.'],
    };

    it('the button fills in the detected type and commands and shows what was found', async () => {
      const fixture = await setup();
      const el: HTMLElement = fixture.nativeElement;
      const c = fixture.componentInstance as unknown as Form;
      fill(el, 'repositoryUrl', 'https://github.com/123sajal/Employee-Management-.git');
      fill(el, 'rootDirectory', 'backend');
      fill(el, 'startCommand', 'something I typed');
      api.detect.mockReturnValue(of(backend));

      c.detect();
      fixture.detectChanges();

      expect(api.detect).toHaveBeenCalledWith('https://github.com/123sajal/Employee-Management-.git', 'main', 'backend', null, null);
      expect(c.form.controls.appType.value).toBe('NODE_JS');
      expect(c.form.controls.buildCommand.value).toBe('npm ci');
      expect(c.form.controls.startCommand.value).toBe('npm run db:setup && npm start');
      expect(el.textContent).toContain('Detected: Node.js');
      expect(el.textContent).toContain('db:setup');
    });

    it('checks by itself on a new project, once per repository and folder, keeping commands you wrote', async () => {
      const fixture = await setup();
      const el: HTMLElement = fixture.nativeElement;
      const c = fixture.componentInstance as unknown as Form;
      api.detect.mockReset();
      api.detect.mockReturnValue(of(backend));
      fill(el, 'repositoryUrl', 'https://github.com/acme/ems');
      fill(el, 'buildCommand', 'make all');
      fill(el, 'startCommand', './run.sh');

      c.autoDetect();
      c.autoDetect(); // nothing changed: no second check

      expect(api.detect).toHaveBeenCalledTimes(1);
      expect(c.form.controls.appType.value).toBe('NODE_JS');
      expect(c.form.controls.startCommand.value).toBe('./run.sh');
    });

    it('offers the app folders it found and checks the chosen one', async () => {
      const fixture = await setup();
      const el: HTMLElement = fixture.nativeElement;
      const c = fixture.componentInstance as unknown as Form;
      fill(el, 'repositoryUrl', 'https://github.com/acme/ems');
      api.detect.mockReturnValueOnce(of({
        appType: null, buildCommand: null, startCommand: null, appFolders: ['backend', 'frontend'],
        notes: ['No app in the repository\'s main folder, but these folders look like apps: backend, frontend.'],
      })).mockReturnValueOnce(of(backend));

      c.detect();
      fixture.detectChanges();
      expect(el.textContent).toContain('No app found here');
      expect(el.textContent).toContain('frontend');

      c.chooseAppFolder('backend');

      expect(c.form.controls.rootDirectory.value).toBe('backend');
      expect(api.detect).toHaveBeenLastCalledWith('https://github.com/acme/ems', 'main', 'backend', null, null);
      expect(c.form.controls.startCommand.value).toBe('npm run db:setup && npm start');
    });

    it('asks for the repository URL first', async () => {
      const fixture = await setup();
      const c = fixture.componentInstance as unknown as Form;
      api.detect.mockReset();

      c.detect();

      expect(api.detect).not.toHaveBeenCalled();
      expect(c.detectError()).toContain('repository URL');
    });
  });

  describe('private repositories', () => {
    type Form = { form: ProjectFormComponent['form']; detect(force?: boolean): void };

    it('sends the access token only when the repository is marked private', async () => {
      const fixture = await setup();
      const el: HTMLElement = fixture.nativeElement;
      const c = fixture.componentInstance as unknown as Form;
      c.form.patchValue({ appType: 'NODE_JS', isPrivate: true });
      fixture.detectChanges();
      fill(el, 'name', 'secret-app');
      fill(el, 'repositoryUrl', 'https://github.com/acme/secret-app');
      fill(el, 'repositoryToken', ' github_pat_abc ');
      expect(el.textContent).toContain('Contents: Read-only');
      api.create.mockReturnValue(of({ id: 2, name: 'secret-app' }));

      el.querySelector('form')!.dispatchEvent(new Event('submit'));
      await fixture.whenStable();
      expect(api.create.mock.calls[0][0].repositoryToken).toBe('github_pat_abc');
      expect(api.create.mock.calls[0][0].isPrivate).toBeUndefined();

      api.create.mockClear();
      c.form.patchValue({ isPrivate: false });
      el.querySelector('form')!.dispatchEvent(new Event('submit'));
      await fixture.whenStable();
      expect(api.create.mock.calls[0][0].repositoryToken).toBeNull();
    });

    it('detection uses the typed token, and a repository it cannot read opens the token box', async () => {
      const fixture = await setup();
      const el: HTMLElement = fixture.nativeElement;
      const c = fixture.componentInstance as unknown as Form;
      fill(el, 'repositoryUrl', 'https://github.com/acme/secret-app');
      api.detect.mockReset().mockReturnValue(of({
        appType: null, buildCommand: null, startCommand: null, appFolders: [],
        notes: ["The repository wasn't found or isn't public. If it's private, add an access token."],
      }));

      c.detect();

      expect(c.form.controls.isPrivate.value).toBe(true);
      c.form.controls.repositoryToken.setValue('github_pat_xyz');
      c.detect();
      expect(api.detect).toHaveBeenLastCalledWith('https://github.com/acme/secret-app', 'main', null, 'github_pat_xyz', null);
    });

    it('on the Edit page a saved token is kept unless replaced, and can be removed', async () => {
      api.get.mockReturnValue(of({
        id: 4, name: 'secret-app', repositoryUrl: 'https://github.com/acme/secret-app', branch: 'main', appType: 'NODE_JS',
        rootDirectory: null, buildCommand: 'npm ci', startCommand: 'npm start', appPort: null, healthCheckPath: null,
        privateRepository: true, repositoryTokenUpdatedAt: '2026-10-27T09:00:00Z',
      }));
      await TestBed.configureTestingModule({
        imports: [ProjectFormComponent],
        providers: [provideRouter([]), MessageService, { provide: ProjectApiService, useValue: api }],
      }).compileComponents();
      const fixture = TestBed.createComponent(ProjectFormComponent);
      fixture.componentRef.setInput('id', '4');
      await fixture.whenStable();
      fixture.detectChanges();
      const el: HTMLElement = fixture.nativeElement;
      expect(el.textContent).toContain('A token is saved');
      api.removeRepositoryToken.mockReturnValue(of({}));

      [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Remove token'))!.click();

      expect(api.removeRepositoryToken).toHaveBeenCalledWith(4);
      const c = fixture.componentInstance as unknown as Form;
      expect(c.form.controls.isPrivate.value).toBe(false);
    });

    it('ticking private without a token, and none saved, is stopped with a message (review #9)', async () => {
      const fixture = await setup();
      const el: HTMLElement = fixture.nativeElement;
      const c = fixture.componentInstance as unknown as Form;
      c.form.patchValue({ appType: 'NODE_JS', isPrivate: true });
      fixture.detectChanges(); // ticking the box shows the token field, as on screen
      fill(el, 'name', 'secret-app');
      fill(el, 'repositoryUrl', 'https://github.com/acme/secret-app');
      api.create.mockClear();

      el.querySelector('form')!.dispatchEvent(new Event('submit'));
      fixture.detectChanges();

      expect(api.create).not.toHaveBeenCalled();
      expect(el.querySelector('#token-error')?.textContent).toContain('Paste the access token');
    });

    it('on the Edit page, unticking private removes the saved token, and a new URL warns (review #2, #8)', async () => {
      api.get.mockReturnValue(of({
        id: 4, name: 'secret-app', repositoryUrl: 'https://github.com/acme/secret-app', branch: 'main', appType: 'NODE_JS',
        rootDirectory: null, buildCommand: 'npm ci', startCommand: 'npm start', appPort: null, healthCheckPath: null,
        privateRepository: true, repositoryTokenUpdatedAt: '2026-10-27T09:00:00Z',
      }));
      await TestBed.configureTestingModule({
        imports: [ProjectFormComponent],
        providers: [provideRouter([]), MessageService, { provide: ProjectApiService, useValue: api }],
      }).compileComponents();
      vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      const fixture = TestBed.createComponent(ProjectFormComponent);
      fixture.componentRef.setInput('id', '4');
      await fixture.whenStable();
      fixture.detectChanges();
      const el: HTMLElement = fixture.nativeElement;
      const c = fixture.componentInstance as unknown as Form;

      fill(el, 'repositoryUrl', 'https://github.com/acme/renamed');
      fixture.detectChanges();
      expect(el.textContent).toContain('the saved token belongs to the old repository');

      fill(el, 'repositoryUrl', 'https://github.com/acme/secret-app');
      c.form.controls.isPrivate.setValue(false);
      api.update.mockReset().mockReturnValue(of({ id: 4, name: 'secret-app' }));
      api.removeRepositoryToken.mockReset().mockReturnValue(of({ id: 4, name: 'secret-app' }));
      el.querySelector('form')!.dispatchEvent(new Event('submit'));
      await fixture.whenStable();

      expect(api.update).toHaveBeenCalledTimes(1);
      expect(api.removeRepositoryToken).toHaveBeenCalledWith(4);
    });
  });
});
