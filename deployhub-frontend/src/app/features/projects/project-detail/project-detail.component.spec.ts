import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { NEVER, of } from 'rxjs';

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
