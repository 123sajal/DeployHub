import { TestBed } from '@angular/core/testing';
import { ConfirmationService, MessageService } from 'primeng/api';
import { of } from 'rxjs';

import { PublicLink } from '../../../core/models/api.models';
import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { ProjectPublicLinkComponent } from './project-public-link.component';

const OFF: PublicLink = { enabled: false, url: null, localUrl: 'http://localhost:19001' };
const ON: PublicLink = { enabled: true, url: 'https://brave-ocean.trycloudflare.com', localUrl: 'http://localhost:19001' };

describe('ProjectPublicLinkComponent', () => {
  async function render(api: Record<string, unknown>) {
    await TestBed.configureTestingModule({
      imports: [ProjectPublicLinkComponent],
      providers: [MessageService, ConfirmationService, { provide: ProjectApiService, useValue: api }],
    }).compileComponents();
    const fixture = TestBed.createComponent(ProjectPublicLinkComponent);
    fixture.componentRef.setInput('projectId', 42);
    const emitted: PublicLink[] = [];
    fixture.componentInstance.changed.subscribe((l) => emitted.push(l));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, emitted };
  }

  it('is private by default and makes the app public on request', async () => {
    const enablePublicLink = vi.fn(() => of(ON));
    const { fixture, emitted } = await render({ getPublicLink: () => of(OFF), enablePublicLink });
    expect(fixture.nativeElement.textContent).toContain('Private');

    (fixture.componentInstance as unknown as { enable(): void }).enable();
    fixture.detectChanges();

    expect(enablePublicLink).toHaveBeenCalledWith(42);
    expect(fixture.nativeElement.querySelector('a')?.getAttribute('href')).toBe(ON.url);
    expect(emitted.at(-1)).toEqual(ON); // so "Open app" switches to the public link
  });

  it('shows the current link and offers to turn it off', async () => {
    const { fixture, emitted } = await render({ getPublicLink: () => of(ON) });
    expect(fixture.nativeElement.textContent).toContain('Turn off public link');
    expect(fixture.nativeElement.textContent).toContain('brave-ocean.trycloudflare.com');
    expect(emitted).toEqual([ON]);
  });
});
