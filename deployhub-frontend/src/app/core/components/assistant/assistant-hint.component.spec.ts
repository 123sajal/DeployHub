import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { AssistantApiService } from '../../services/api/assistant-api.service';
import { AssistantHintComponent } from './assistant-hint.component';

describe('AssistantHintComponent', () => {
  const api = { explain: vi.fn() };

  async function render(inputs: Record<string, unknown>) {
    api.explain.mockReset();
    api.explain.mockReturnValue(of({ text: 'Set the App folder to backend.', source: 'AI', notice: null, saved: false }));
    await TestBed.configureTestingModule({
      imports: [AssistantHintComponent],
      providers: [{ provide: AssistantApiService, useValue: api }],
    }).compileComponents();
    const fixture = TestBed.createComponent(AssistantHintComponent);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    await fixture.whenStable();
    return fixture;
  }

  it('offers to explain a failed deployment and only asks when clicked', async () => {
    const fixture = await render({ topic: 'DEPLOYMENT_FAILED', deploymentId: 7 });
    expect(api.explain).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Want me to read the log');

    fixture.nativeElement.querySelector('button.p-button').click();
    await fixture.whenStable();
    expect(api.explain).toHaveBeenCalledWith('DEPLOYMENT_FAILED', { deploymentId: 7, projectId: undefined, errorText: undefined });
    expect(fixture.nativeElement.textContent).toContain('Set the App folder to backend.');
    expect(fixture.nativeElement.textContent).toContain('Written by AI');
  });

  it('explains right away in auto mode', async () => {
    const fixture = await render({ topic: 'PAYMENT_FAILED', errorText: 'Payment failed: declined', auto: true });
    expect(api.explain).toHaveBeenCalledWith('PAYMENT_FAILED', expect.objectContaining({ errorText: 'Payment failed: declined' }));
    expect(fixture.nativeElement.textContent).toContain('Set the App folder to backend.');
  });

  it('offers to ask the AI again after a built-in tip', async () => {
    const fixture = await render({ topic: 'DEPLOYMENT_FAILED', deploymentId: 9 });
    api.explain.mockReturnValueOnce(
      of({ text: 'Things to check', source: 'BUILT_IN', notice: 'The AI is not reachable right now.', saved: false }),
    );
    fixture.nativeElement.querySelector('button.p-button').click();
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('The AI is not reachable right now.');

    const again = [...fixture.nativeElement.querySelectorAll('button')].find((b) =>
      (b as HTMLElement).textContent?.includes('Ask the AI again'),
    ) as HTMLButtonElement;
    again.click();
    await fixture.whenStable();
    expect(api.explain).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Set the App folder to backend.');
  });

  it('can be closed', async () => {
    const fixture = await render({ topic: 'IMPORT_REFUSED', errorText: 'Tables exist', auto: true });
    fixture.nativeElement.querySelector('button.close').click();
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('aside')).toBeNull();
  });
});
