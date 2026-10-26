import { DatePipe } from '@angular/common';
import {
  Component,
  DestroyRef,
  ElementRef,
  afterRenderEffect,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { EMPTY, Subscription, catchError, concatMap, expand, timer } from 'rxjs';

import { ApiError, DeploymentLog } from '../../../core/models/api.models';
import { DeploymentApiService } from '../../../core/services/api/deployment-api.service';
import { isInProgress } from '../../../core/utils/deployment-status';
import { isTransient } from '../../../core/utils/polling';

const POLL_MS = 1500;
const RETRY_MS = 3000;
/** Cap what the browser keeps in memory; older lines remain on the server. */
const MAX_LINES = 5000;

const RECONNECTING: ApiError = { status: 0, title: 'Reconnecting', detail: 'Connection lost', fieldErrors: {} };

/**
 * Follows a deployment's logs. Preferred: Server-Sent Events, where the server pushes each line as it is
 * written. Fallback: cursor polling, used when the browser has no EventSource or the server refuses the
 * stream (e.g. too many open streams). Both use the same cursor (the last log id seen), so switching or
 * reconnecting never causes gaps or duplicates.
 */
@Component({
  selector: 'app-log-viewer',
  imports: [DatePipe, ButtonModule],
  templateUrl: './log-viewer.component.html',
  styleUrl: './log-viewer.component.scss',
})
export class LogViewerComponent {
  readonly deploymentId = input.required<number>();

  private readonly deploymentApi = inject(DeploymentApiService);
  private readonly messages = inject(MessageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');
  private polling?: Subscription;
  private retry?: Subscription;
  private source?: EventSource;
  private cursor = 0;

  protected readonly lines = signal<DeploymentLog[]>([]);
  protected readonly following = signal(true);
  protected readonly finished = signal(false);
  /** How lines arrive right now: pushed by the server ('live') or fetched every 1.5 s ('polling'). */
  protected readonly mode = signal<'live' | 'polling'>('live');
  protected readonly connectionError = signal<ApiError | null>(null);
  /** An error that retrying won't fix (e.g. 404): polling has stopped for good. */
  protected readonly fatalError = signal<ApiError | null>(null);
  protected readonly autoScroll = signal(true);

  constructor() {
    afterRenderEffect(() => {
      this.lines();
      const el = this.scroller()?.nativeElement;
      if (el && this.autoScroll()) el.scrollTop = el.scrollHeight;
    });
    // Start over whenever the deployment changes (the router reuses this component, e.g. after a rollback).
    effect(() => {
      this.deploymentId();
      untracked(() => this.restart());
    });
    this.destroyRef.onDestroy(() => this.stop());
  }

  private restart(): void {
    this.stop();
    this.cursor = 0;
    this.lines.set([]);
    this.finished.set(false);
    this.fatalError.set(null);
    this.follow();
  }

  protected follow(): void {
    this.stop();
    this.following.set(true);
    this.connectionError.set(null);
    if (typeof EventSource === 'undefined') {
      this.poll();
    } else {
      this.stream();
    }
  }

  private stop(): void {
    this.source?.close();
    this.source = undefined;
    this.polling?.unsubscribe();
    this.retry?.unsubscribe();
  }

  private stream(): void {
    this.mode.set('live');
    const source = new EventSource(this.deploymentApi.logStreamUrl(this.deploymentId(), this.cursor));
    this.source = source;
    source.onopen = () => this.connectionError.set(null);
    source.addEventListener('log', (event) => {
      this.connectionError.set(null);
      this.append([JSON.parse((event as MessageEvent<string>).data) as DeploymentLog]);
    });
    source.addEventListener('end', () => {
      // Finished and everything sent. Close, or the browser would reconnect.
      source.close();
      this.finished.set(true);
      this.following.set(false);
    });
    source.onerror = () => {
      if (source.readyState === EventSource.CLOSED) {
        // The server refused the stream (401, 404, 429...). Polling reports the real reason, or works anyway.
        source.close();
        this.poll();
      } else {
        // The connection dropped; the browser reconnects by itself and resumes via Last-Event-ID.
        this.connectionError.set(RECONNECTING);
      }
    };
  }

  private poll(): void {
    this.mode.set('polling');
    // Fetch a page; if more is waiting fetch again at once, otherwise wait POLL_MS.
    // Stop once the deployment is finished and the backlog is drained.
    const fetchPage = () => this.deploymentApi.logs(this.deploymentId(), this.cursor);
    this.polling = fetchPage()
      .pipe(
        expand((page) => {
          this.connectionError.set(null);
          this.append(page.items);
          if (!page.hasMore && !isInProgress(page.deploymentStatus)) {
            this.finished.set(true);
            this.following.set(false);
            return EMPTY;
          }
          return timer(page.hasMore ? 0 : POLL_MS).pipe(concatMap(fetchPage));
        }),
        catchError((err: ApiError) => {
          this.following.set(false);
          if (!isTransient(err)) {
            this.fatalError.set(err);
            return EMPTY;
          }
          this.connectionError.set(err);
          // Reconnect (trying the live stream again) from the same cursor after a short pause.
          this.retry = timer(RETRY_MS)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.follow());
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();
  }

  /** Adds lines after the cursor; anything at or before it was already shown (e.g. replayed after a reconnect). */
  private append(items: DeploymentLog[]): void {
    const fresh = items.filter((line) => line.id > this.cursor);
    if (fresh.length === 0) return;
    this.cursor = fresh[fresh.length - 1].id;
    this.lines.update((current) => [...current, ...fresh].slice(-MAX_LINES));
  }

  protected onScroll(el: HTMLElement): void {
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
    this.autoScroll.set(atBottom);
  }

  protected async copy(): Promise<void> {
    const text = this.lines()
      .map((l) => `${l.occurredAt} ${l.level.padEnd(5)} ${l.message}`)
      .join('\n');
    try {
      await navigator.clipboard.writeText(text);
      this.messages.add({ severity: 'success', summary: 'Logs copied', detail: `${this.lines().length} lines` });
    } catch {
      this.messages.add({ severity: 'error', summary: 'Copy failed', detail: 'Clipboard access was denied.' });
    }
  }
}
