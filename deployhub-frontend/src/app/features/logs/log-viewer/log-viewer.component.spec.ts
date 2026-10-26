import { TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';
import { of, throwError } from 'rxjs';

import { DeploymentLog, DeploymentStatus, LogPage } from '../../../core/models/api.models';
import { DeploymentApiService } from '../../../core/services/api/deployment-api.service';
import { LogViewerComponent } from './log-viewer.component';

const line = (id: number): DeploymentLog => ({ id, level: 'INFO', message: `line ${id}`, occurredAt: '2026-01-01T00:00:00Z' });
const page = (ids: number[], status: DeploymentStatus, hasMore = false): LogPage => ({
  items: ids.map(line),
  nextCursor: ids.at(-1) ?? 0,
  hasMore,
  deploymentStatus: status,
});

describe('LogViewerComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('resumes from the last cursor after a connection error, with no gaps or duplicates', async () => {
    const logs = vi
      .fn()
      .mockReturnValueOnce(of(page([1, 2], 'BUILDING')))
      .mockReturnValueOnce(throwError(() => ({ status: 0, title: 'API unreachable', detail: '', fieldErrors: {} })))
      .mockReturnValueOnce(of(page([3, 4], 'LIVE', true)))
      .mockReturnValueOnce(of(page([5], 'LIVE')));

    await TestBed.configureTestingModule({
      imports: [LogViewerComponent],
      providers: [MessageService, { provide: DeploymentApiService, useValue: { logs } }],
    }).compileComponents();
    const fixture = TestBed.createComponent(LogViewerComponent);
    fixture.componentRef.setInput('deploymentId', 11);
    fixture.detectChanges();

    await vi.advanceTimersByTimeAsync(1500); // poll -> error
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('reconnecting');

    await vi.advanceTimersByTimeAsync(3000); // retry from cursor 2
    await vi.advanceTimersByTimeAsync(10); // hasMore -> next page fetched without waiting
    fixture.detectChanges();

    expect(logs.mock.calls.map((c) => c[1])).toEqual([0, 2, 2, 4]);
    const rendered = Array.from(fixture.nativeElement.querySelectorAll('.msg')).map((m) => (m as HTMLElement).textContent);
    expect(rendered).toEqual(['line 1', 'line 2', 'line 3', 'line 4', 'line 5']);
    expect(fixture.nativeElement.textContent).toContain('Complete');
  });

  it('stops for good on an error that reconnecting cannot fix (e.g. deployment deleted)', async () => {
    const logs = vi
      .fn()
      .mockReturnValueOnce(of(page([1], 'BUILDING')))
      .mockReturnValue(throwError(() => ({ status: 404, title: 'Not found', detail: 'Deployment 11 was not found', fieldErrors: {} })));

    await TestBed.configureTestingModule({
      imports: [LogViewerComponent],
      providers: [MessageService, { provide: DeploymentApiService, useValue: { logs } }],
    }).compileComponents();
    const fixture = TestBed.createComponent(LogViewerComponent);
    fixture.componentRef.setInput('deploymentId', 11);
    fixture.detectChanges();

    await vi.advanceTimersByTimeAsync(20000);
    fixture.detectChanges();

    expect(logs).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Deployment 11 was not found');
    expect(fixture.nativeElement.textContent).not.toContain('reconnecting');
  });

  describe('with Server-Sent Events', () => {
    /** Minimal stand-in for the browser's EventSource (jsdom has none). */
    class FakeEventSource {
      static readonly CONNECTING = 0;
      static readonly OPEN = 1;
      static readonly CLOSED = 2;
      static instances: FakeEventSource[] = [];
      readyState = FakeEventSource.CONNECTING;
      onopen: (() => void) | null = null;
      onerror: (() => void) | null = null;
      private listeners = new Map<string, ((e: MessageEvent) => void)[]>();

      constructor(readonly url: string) {
        FakeEventSource.instances.push(this);
      }
      addEventListener(type: string, listener: (e: MessageEvent) => void): void {
        this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
      }
      emit(type: string, data: unknown): void {
        this.readyState = FakeEventSource.OPEN;
        for (const listener of this.listeners.get(type) ?? []) listener({ data: JSON.stringify(data) } as MessageEvent);
      }
      fail(readyState: number): void {
        this.readyState = readyState;
        this.onerror?.();
      }
      close(): void {
        this.readyState = FakeEventSource.CLOSED;
      }
    }

    beforeEach(() => {
      FakeEventSource.instances = [];
      vi.stubGlobal('EventSource', FakeEventSource);
    });
    afterEach(() => vi.unstubAllGlobals());

    async function renderStreaming(logs = vi.fn()) {
      await TestBed.configureTestingModule({
        imports: [LogViewerComponent],
        providers: [
          MessageService,
          {
            provide: DeploymentApiService,
            useValue: { logs, logStreamUrl: (id: number, after: number) => `/api/deployments/${id}/logs/stream?after=${after}` },
          },
        ],
      }).compileComponents();
      const fixture = TestBed.createComponent(LogViewerComponent);
      fixture.componentRef.setInput('deploymentId', 11);
      fixture.detectChanges();
      return fixture;
    }

    const rendered = (fixture: { nativeElement: HTMLElement }) =>
      Array.from(fixture.nativeElement.querySelectorAll('.msg')).map((m) => m.textContent);

    it('shows lines as the server pushes them and closes the stream at the end, without polling', async () => {
      const logs = vi.fn();
      const fixture = await renderStreaming(logs);
      const source = FakeEventSource.instances[0];
      expect(source.url).toBe('/api/deployments/11/logs/stream?after=0');

      source.emit('log', line(1));
      source.emit('log', line(2));
      source.emit('log', line(2)); // replayed after a reconnect: shown once
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('live stream');

      source.emit('end', { status: 'LIVE' });
      fixture.detectChanges();

      expect(rendered(fixture)).toEqual(['line 1', 'line 2']);
      expect(source.readyState).toBe(FakeEventSource.CLOSED);
      expect(fixture.nativeElement.textContent).toContain('Complete');
      expect(logs).not.toHaveBeenCalled();
    });

    it('lets the browser reconnect by itself after a dropped connection', async () => {
      const fixture = await renderStreaming();
      const source = FakeEventSource.instances[0];
      source.emit('log', line(1));
      source.fail(FakeEventSource.CONNECTING);
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('reconnecting');

      source.emit('log', line(2));
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).not.toContain('reconnecting');
      expect(FakeEventSource.instances).toHaveLength(1);
    });

    it('falls back to polling from the same cursor when the server refuses the stream', async () => {
      const logs = vi.fn().mockReturnValue(of(page([3, 4], 'LIVE')));
      const fixture = await renderStreaming(logs);
      const source = FakeEventSource.instances[0];
      source.emit('log', line(1));
      source.emit('log', line(2));

      source.fail(FakeEventSource.CLOSED); // e.g. 429: too many open streams
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();

      expect(logs).toHaveBeenCalledWith(11, 2);
      expect(rendered(fixture)).toEqual(['line 1', 'line 2', 'line 3', 'line 4']);
      expect(fixture.nativeElement.textContent).toContain('Complete');
    });
  });
});
