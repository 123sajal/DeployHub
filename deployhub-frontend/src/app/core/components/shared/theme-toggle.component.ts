import { Component, inject } from '@angular/core';

import { ThemeService } from '../../services/theme/theme.service';

/** Sun/moon button that switches between the light and the dark theme. */
@Component({
  selector: 'app-theme-toggle',
  template: `
    <button type="button" class="toggle" (click)="theme.toggle()"
            [attr.aria-label]="theme.theme() === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'"
            [attr.title]="theme.theme() === 'dark' ? 'Light mode' : 'Dark mode'">
      <i class="pi" [class.pi-sun]="theme.theme() === 'dark'" [class.pi-moon]="theme.theme() !== 'dark'"
         aria-hidden="true"></i>
    </button>
  `,
  styles: `
    .toggle { display: grid; place-items: center; width: 2.25rem; height: 2.25rem; border-radius: 999px;
      border: 1px solid var(--dh-border-strong); background: var(--dh-surface); color: var(--dh-text-muted);
      cursor: pointer; }
    .toggle:hover { color: var(--dh-primary); border-color: var(--dh-primary); }
    .toggle:focus-visible { outline: 2px solid var(--dh-primary); outline-offset: 2px; }
  `,
})
export class ThemeToggleComponent {
  protected readonly theme = inject(ThemeService);
}
