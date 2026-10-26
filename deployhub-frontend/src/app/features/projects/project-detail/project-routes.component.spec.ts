import { TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';
import { of } from 'rxjs';

import { ProjectRoute } from '../../../core/models/api.models';
import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { ProjectRoutesComponent } from './project-routes.component';

describe('ProjectRoutesComponent', () => {
  const projects = { items: [{ id: 4, name: 'web' }, { id: 5, name: 'api' }], page: 0, size: 100, totalElements: 2, totalPages: 1 };

  async function render(api: Record<string, unknown>) {
    await TestBed.configureTestingModule({
      imports: [ProjectRoutesComponent],
      providers: [MessageService, { provide: ProjectApiService, useValue: { list: () => of(projects), ...api } }],
    }).compileComponents();
    const fixture = TestBed.createComponent(ProjectRoutesComponent);
    fixture.componentRef.setInput('projectId', 4);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.componentInstance as unknown as {
      routes(): ProjectRoute[];
      targets(): { id: number }[];
      add(): void;
      save(): void;
    };
  }

  it('offers the other projects as targets and saves the routes', async () => {
    const replaceRoutes = vi.fn((_id: number, routes: ProjectRoute[]) => of(routes));
    const component = await render({ listRoutes: () => of([]), replaceRoutes });

    expect(component.targets().map((p) => p.id)).toEqual([5]);
    component.add();
    component.save();

    expect(replaceRoutes).toHaveBeenCalledWith(4, [{ pathPrefix: '/api', targetProjectId: 5 }]);
  });

  it('does not save a route without a valid path', async () => {
    const replaceRoutes = vi.fn();
    const component = await render({
      listRoutes: () => of([{ pathPrefix: 'api', targetProjectId: 5, targetProjectName: 'api' }]),
      replaceRoutes,
    });

    component.save();

    expect(replaceRoutes).not.toHaveBeenCalled();
  });
});
