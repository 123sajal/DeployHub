import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AssistantApiService } from '../../services/api/assistant-api.service';
import { AssistantChatComponent } from './assistant-chat.component';

describe('AssistantChatComponent', () => {
  const api = { chat: vi.fn() };

  async function render() {
    api.chat.mockReset();
    await TestBed.configureTestingModule({
      imports: [AssistantChatComponent],
      providers: [provideRouter([]), { provide: AssistantApiService, useValue: api }],
    }).compileComponents();
    const fixture = TestBed.createComponent(AssistantChatComponent);
    fixture.componentRef.setInput('page', 'login');
    await fixture.whenStable();
    fixture.nativeElement.querySelector('button.launcher').click();
    await fixture.whenStable();
    return fixture;
  }

  async function ask(fixture: Awaited<ReturnType<typeof render>>, question: string) {
    const box = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    box.value = question;
    box.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('sends the conversation with the page and shows the answer', async () => {
    const fixture = await render();
    api.chat.mockReturnValue(of({ text: 'Click Forgot password.', source: 'AI', notice: null, saved: false }));
    await ask(fixture, 'I forgot my password');
    expect(api.chat).toHaveBeenCalledWith('login', [
      expect.objectContaining({ role: 'ASSISTANT' }),
      { role: 'USER', text: 'I forgot my password' },
    ]);
    expect(fixture.nativeElement.textContent).toContain('Click Forgot password.');
  });

  it('shows why a simple answer was given, and errors without breaking the chat', async () => {
    const fixture = await render();
    api.chat.mockReturnValueOnce(of({ text: 'Simple answer.', source: 'BUILT_IN', notice: "Today's limit is reached.", saved: false }));
    await ask(fixture, 'price?');
    expect(fixture.nativeElement.textContent).toContain("Today's limit is reached.");

    api.chat.mockReturnValueOnce(throwError(() => ({ status: 0, title: '', detail: '', fieldErrors: {} })));
    await ask(fixture, 'hello');
    expect(fixture.nativeElement.textContent).toContain("can't reach DeployHub");
  });
});
