import { Component } from '@angular/core';
import { TooltipModule } from 'primeng/tooltip';

/** Shown next to deployments made by the simulator (execution mode SIMULATED): nothing was built or run. */
@Component({
  selector: 'app-simulated-badge',
  imports: [TooltipModule],
  template: `<span
    class="sim"
    pTooltip="No code was built or run. An in-process simulator moved this deployment through its states."
    tooltipPosition="top"
    tabindex="0"
    ><i class="pi pi-sparkles" aria-hidden="true"></i> Simulated</span
  >`,
  styles: `
    .sim {
      display: inline-flex; align-items: center; gap: 0.3rem; padding: 0.1rem 0.5rem;
      border: 1px dashed var(--dh-warning); color: var(--dh-warning); border-radius: 999px;
      font-size: 0.72rem; font-weight: 600; white-space: nowrap;
    }
    .pi { font-size: 0.7rem; }
  `,
})
export class SimulatedBadgeComponent {}
