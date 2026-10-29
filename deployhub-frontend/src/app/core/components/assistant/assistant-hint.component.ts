import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { ButtonModule } from 'primeng/button';

import { ApiError, AssistantReply, AssistantTopic } from '../../models/api.models';
import { AssistantApiService } from '../../services/api/assistant-api.service';
import { AssistantAvatarComponent } from './assistant-avatar.component';

const INTRO: Record<AssistantTopic, string> = {
  DEPLOYMENT_FAILED: 'This deployment failed. Want me to read the log and explain what went wrong and how to fix it?',
  MISSING_SETTINGS: 'Your app needs a few settings before it can run. Here is what they mean:',
  PAYMENT_FAILED: 'The payment didn\'t go through. Here is what you can do:',
  IMPORT_REFUSED: 'The import was refused. Here is how to fix it:',
};

/**
 * The assistant popping up next to a problem (failed deployment, missing settings, failed payment, refused import).
 * With auto, it explains right away (cheap topics); otherwise it offers to, because reading a whole deployment log
 * uses the more expensive model. It can be closed and never blocks the page.
 */
@Component({
  selector: 'app-assistant-hint',
  imports: [ButtonModule, AssistantAvatarComponent],
  template: `
    @if (!closed()) {
      <aside class="hint" aria-live="polite" aria-label="DeployHub assistant">
        <app-assistant-avatar [size]="32" />
        <div class="body">
          <div class="top">
            <strong>DeployHub assistant</strong>
            <button type="button" class="close" (click)="closed.set(true)" aria-label="Close the assistant's tip">
              <i class="pi pi-times" aria-hidden="true"></i>
            </button>
          </div>
          @if (reply(); as r) {
            @if (r.notice) {
              <small class="notice">{{ r.notice }}</small>
            }
            <p class="answer">{{ r.text }}</p>
            <small class="faint">
              {{ r.source === 'AI' ? 'Written by AI from your settings and log. Check before changing things.' : 'Built-in tip.' }}
            </small>
            @if (r.source === 'BUILT_IN') {
              <p-button label="Ask the AI again" icon="pi pi-refresh" size="small" [text]="true" (onClick)="ask()" />
            }
          } @else if (loading()) {
            <p class="faint">Reading what happened...</p>
          } @else if (error(); as message) {
            <p class="error">{{ message }}</p>
            <p-button label="Try again" size="small" [text]="true" (onClick)="ask()" />
          } @else {
            <p>{{ intro() }}</p>
            <p-button [label]="topic() === 'DEPLOYMENT_FAILED' ? 'Explain this failure' : 'Explain this'" icon="pi pi-sparkles" size="small" (onClick)="ask()" />
          }
        </div>
      </aside>
    }
  `,
  styles: `
    .hint { display: flex; gap: 0.75rem; padding: 0.9rem 1rem; margin: 1rem 0; border-radius: var(--dh-radius-lg);
      background: color-mix(in srgb, var(--dh-accent) 9%, var(--dh-surface));
      border: 1px solid color-mix(in srgb, var(--dh-accent) 40%, transparent); }
    .body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 0.4rem; }
    .top { display: flex; align-items: center; justify-content: space-between; }
    .close { border: 0; background: transparent; color: var(--dh-text-muted); cursor: pointer; border-radius: 6px;
      width: 1.8rem; height: 1.8rem; }
    .close:hover { color: var(--dh-text); }
    p { margin: 0; }
    .answer { white-space: pre-wrap; word-break: break-word; }
    .notice { color: var(--dh-warning); }
    .faint { color: var(--dh-text-faint); }
    .error { color: var(--dh-danger); }
  `,
})
export class AssistantHintComponent {
  readonly topic = input.required<AssistantTopic>();
  readonly deploymentId = input<number>();
  readonly projectId = input<number>();
  /** The error the page shows; a new one reopens the tip. */
  readonly errorText = input<string>();
  /** Explain right away instead of offering to. */
  readonly auto = input(false);

  private readonly api = inject(AssistantApiService);

  protected readonly closed = signal(false);
  protected readonly loading = signal(false);
  protected readonly reply = signal<AssistantReply | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly intro = () => INTRO[this.topic()];

  constructor() {
    effect(() => {
      // A new problem (another deployment or another error) starts over.
      this.topic();
      this.deploymentId();
      this.errorText();
      const auto = this.auto();
      untracked(() => {
        this.closed.set(false);
        this.reply.set(null);
        this.error.set(null);
        if (auto) this.ask();
      });
    });
  }

  protected ask(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .explain(this.topic(), { deploymentId: this.deploymentId(), projectId: this.projectId(), errorText: this.errorText() })
      .subscribe({
        next: (reply) => {
          this.reply.set(reply);
          this.loading.set(false);
        },
        error: (err: ApiError) => {
          this.error.set(err.detail || 'The assistant could not answer. Try again in a moment.');
          this.loading.set(false);
        },
      });
  }
}
