import { Component, input, output } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { SkeletonModule } from 'primeng/skeleton';

import { ApiError } from '../../models/api.models';

/** One component for the loading / error / empty states every data page needs. */
@Component({
  selector: 'app-page-state',
  imports: [ButtonModule, SkeletonModule],
  template: `
    @if (loading()) {
      <div class="skeletons" aria-busy="true" aria-live="polite">
        <span class="sr-only">Loading…</span>
        @for (row of rows; track row) {
          <p-skeleton height="2.5rem" />
        }
      </div>
    } @else if (error(); as err) {
      <div class="state" role="alert">
        <i class="pi pi-exclamation-triangle danger" aria-hidden="true"></i>
        <h3>{{ err.title }}</h3>
        <p class="dh-muted">{{ err.detail }}</p>
        <p-button label="Try again" icon="pi pi-refresh" severity="secondary" (onClick)="retry.emit()" />
      </div>
    } @else if (empty()) {
      <div class="state">
        <i class="pi {{ emptyIcon() }}" aria-hidden="true"></i>
        <h3>{{ emptyTitle() }}</h3>
        <p class="dh-muted">{{ emptyText() }}</p>
        <ng-content select="[emptyAction]" />
      </div>
    } @else {
      <ng-content />
    }
  `,
  styles: `
    .skeletons { display: flex; flex-direction: column; gap: 0.6rem; }
    .state { display: flex; flex-direction: column; align-items: center; text-align: center; gap: 0.5rem; padding: 2.5rem 1rem; }
    .state .pi { font-size: 1.8rem; color: var(--dh-text-faint); }
    .state .pi.danger { color: var(--dh-danger); }
    .state p { margin: 0 0 0.5rem; max-width: 420px; }
  `,
})
export class PageStateComponent {
  readonly loading = input(false);
  readonly error = input<ApiError | null>(null);
  readonly empty = input(false);
  readonly emptyIcon = input('pi-inbox');
  readonly emptyTitle = input('Nothing here yet');
  readonly emptyText = input('');
  readonly retry = output<void>();
  protected readonly rows = [1, 2, 3, 4];
}
