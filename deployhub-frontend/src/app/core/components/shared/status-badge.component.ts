import { Component, computed, input } from '@angular/core';
import { TagModule } from 'primeng/tag';

import { DeploymentStatus } from '../../models/api.models';
import { isInProgress, statusLabel, statusSeverity } from '../../utils/deployment-status';

@Component({
  selector: 'app-status-badge',
  imports: [TagModule],
  template: `
    <p-tag [severity]="severity()" [rounded]="true">
      <span class="badge">
        @if (pulsing()) {
          <span class="dot" aria-hidden="true"></span>
        }
        {{ label() }}
      </span>
    </p-tag>
  `,
  styles: `
    .badge { display: inline-flex; align-items: center; gap: 0.4rem; font-weight: 600; font-size: 0.75rem; }
    .dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; animation: pulse 1.2s infinite; }
    @keyframes pulse { 50% { opacity: 0.3; } }
    @media (prefers-reduced-motion: reduce) { .dot { animation: none; } }
  `,
})
export class StatusBadgeComponent {
  readonly status = input.required<DeploymentStatus>();
  protected readonly severity = computed(() => statusSeverity(this.status()));
  protected readonly label = computed(() => statusLabel(this.status()));
  protected readonly pulsing = computed(() => isInProgress(this.status()));
}
