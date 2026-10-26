import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';

import { ApiError, PublicLink } from '../../../core/models/api.models';
import { ProjectApiService } from '../../../core/services/api/project-api.service';

/**
 * Opt-in public internet link for a live project (e.g. the frontend). Projects that only serve others, like a
 * backend reached through a route, stay private: leave this off for them.
 */
@Component({
  selector: 'app-project-public-link',
  imports: [ButtonModule],
  template: `
    <div class="head">
      <h2 class="dh-card-title">Public link</h2>
      @if (link()?.enabled) {
        <span class="badge on"><i class="pi pi-globe" aria-hidden="true"></i> Public</span>
      } @else {
        <span class="badge"><i class="pi pi-lock" aria-hidden="true"></i> Private</span>
      }
    </div>

    @if (error(); as err) {
      <p class="dh-error" role="alert">{{ err.detail }}</p>
    }

    @if (link(); as l) {
      @if (l.enabled && l.url) {
        <p class="url">
          <a class="mono" [href]="l.url" target="_blank" rel="noopener noreferrer">{{ l.url }}</a>
          <p-button icon="pi pi-copy" [text]="true" size="small" ariaLabel="Copy public link" (onClick)="copy(l.url)" />
        </p>
        <p class="dh-muted hint">
          Anyone with this link can open the app while this Mac is on. Open app now opens this link. The link changes
          if the tunnel restarts (e.g. after a restart of your Mac); this page always shows the current one.
        </p>
        <p-button label="Turn off public link" icon="pi pi-lock" severity="secondary" [outlined]="true" size="small"
                  [loading]="working()" (onClick)="confirmDisable()" />
      } @else {
        <p class="dh-muted hint">
          Give this app an https link anyone on the internet can open (a free Cloudflare tunnel; nothing else on this
          Mac becomes reachable). Use it for what people should open, like a frontend. Keep backends private: they are
          reached through a route.
        </p>
        <p-button label="Make public" icon="pi pi-globe" size="small" [loading]="working()" (onClick)="enable()" />
        @if (working()) {
          <p class="dh-muted hint">Creating the link: this can take up to a minute.</p>
        }
      }
    }
  `,
  styles: `
    .head { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; }
    .badge { display: inline-flex; gap: 0.3rem; align-items: center; font-size: 0.78rem; font-weight: 600;
      color: var(--dh-text-muted); }
    .badge.on { color: var(--dh-success); }
    .url { display: flex; align-items: center; gap: 0.25rem; margin: 0.25rem 0; word-break: break-all; }
    .hint { font-size: 0.85rem; margin: 0.25rem 0 0.6rem; }
  `,
})
export class ProjectPublicLinkComponent implements OnInit {
  readonly projectId = input.required<number>();
  readonly changed = output<PublicLink>();

  private readonly api = inject(ProjectApiService);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);

  protected readonly link = signal<PublicLink | null>(null);
  protected readonly working = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  ngOnInit(): void {
    this.api.getPublicLink(this.projectId()).subscribe({
      next: (l) => {
        this.link.set(l);
        this.changed.emit(l); // the page shows the current link, even if the tunnel restarted meanwhile
      },
      error: (err: ApiError) => this.error.set(err),
    });
  }

  protected enable(): void {
    this.working.set(true);
    this.error.set(null);
    this.api.enablePublicLink(this.projectId()).subscribe({
      next: (l) => this.done(l, 'The app is public'),
      error: (err: ApiError) => {
        this.working.set(false);
        this.error.set(err);
      },
    });
  }

  protected confirmDisable(): void {
    this.confirm.confirm({
      header: 'Turn off the public link?',
      message: 'The link stops working at once. A new link (a different address) is created if you turn it on again.',
      icon: 'pi pi-lock',
      acceptLabel: 'Turn off',
      rejectLabel: 'Cancel',
      rejectButtonProps: { severity: 'secondary', text: true },
      accept: () => {
        this.working.set(true);
        this.api.disablePublicLink(this.projectId()).subscribe({
          next: (l) => this.done(l, 'The app is private again'),
          error: (err: ApiError) => {
            this.working.set(false);
            this.error.set(err);
          },
        });
      },
    });
  }

  private done(l: PublicLink, summary: string): void {
    this.working.set(false);
    this.link.set(l);
    this.changed.emit(l);
    this.messages.add({ severity: 'success', summary });
  }

  protected async copy(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.messages.add({ severity: 'success', summary: 'Link copied' });
    } catch {
      this.messages.add({ severity: 'error', summary: 'Copy failed', detail: 'Select the link and copy it.' });
    }
  }
}
