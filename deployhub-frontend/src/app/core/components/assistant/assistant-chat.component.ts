import { Component, ElementRef, inject, input, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { ApiError, ChatTurn } from '../../models/api.models';
import { AssistantApiService } from '../../services/api/assistant-api.service';
import { AssistantAvatarComponent } from './assistant-avatar.component';

interface ChatMessage extends ChatTurn {
  /** Why a simple built-in answer was given instead of the AI's. */
  notice?: string | null;
  error?: boolean;
}

const GREETING = "Hi! I'm the DeployHub assistant. Ask me anything about deploying your app, failed deployments, "
  + 'databases, payments or the student discount.';

/**
 * The "Ask the assistant" button and chat panel. The conversation lives only in this component (the server keeps
 * no chats); it is sent along with each question, so answers can refer to earlier ones.
 */
@Component({
  selector: 'app-assistant-chat',
  imports: [FormsModule, AssistantAvatarComponent],
  templateUrl: './assistant-chat.component.html',
  styleUrl: './assistant-chat.component.scss',
})
export class AssistantChatComponent {
  /** Where the user is, for the AI (e.g. "login"); defaults to the current page address. */
  readonly page = input<string>();

  private readonly api = inject(AssistantApiService);
  private readonly router = inject(Router);
  private readonly list = viewChild<ElementRef<HTMLElement>>('list');

  protected readonly open = signal(false);
  protected readonly sending = signal(false);
  protected readonly messages = signal<ChatMessage[]>([{ role: 'ASSISTANT', text: GREETING }]);
  protected question = '';

  protected toggle(): void {
    this.open.update((open) => !open);
  }

  protected send(): void {
    const text = this.question.trim();
    if (!text || this.sending()) return;
    this.question = '';
    this.messages.update((all) => [...all, { role: 'USER', text }]);
    this.sending.set(true);
    this.scrollDown();
    const history: ChatTurn[] = this.messages()
      .filter((m) => !m.error)
      .map(({ role, text }) => ({ role, text: text.slice(0, 2000) }));
    const page = this.page() ?? this.router.url.split('?')[0];
    this.api.chat(page, history).subscribe({
      next: (reply) => {
        this.messages.update((all) => [...all, { role: 'ASSISTANT', text: reply.text, notice: reply.notice }]);
        this.sending.set(false);
        this.scrollDown();
      },
      error: (err: ApiError) => {
        const text = err.status === 0 ? "I can't reach DeployHub right now. Check your connection and try again."
          : err.detail || 'Something went wrong. Please try again.';
        this.messages.update((all) => [...all, { role: 'ASSISTANT', text, error: true }]);
        this.sending.set(false);
        this.scrollDown();
      },
    });
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.send();
    }
  }

  private scrollDown(): void {
    setTimeout(() => {
      const el = this.list()?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }
}
