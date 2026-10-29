import { Component, input } from '@angular/core';

let nextId = 0;

/**
 * The DeployHub logo: three stacked layers (frontend, backend, database) with an arrow going up, and the name with
 * "Hub" in orange. The parent link carries the accessible name; the drawing itself is decoration.
 */
@Component({
  selector: 'app-logo',
  template: `
    <svg class="mark" [attr.width]="size()" [attr.height]="size()" viewBox="0 0 64 64" aria-hidden="true"
         focusable="false">
      <defs>
        <linearGradient [attr.id]="gradientId" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#fb923c" />
          <stop offset="1" stop-color="#cc4a0a" />
        </linearGradient>
      </defs>
      <path d="M32 38 L56 47 L32 56 L8 47 Z" fill="#fdba74" />
      <path d="M32 26 L56 35 L32 44 L8 35 Z" fill="#fb923c" />
      <path d="M32 12 L56 21 L32 30 L8 21 Z" [attr.fill]="'url(#' + gradientId + ')'" />
      <path d="M32 26 V8 M27 13 L32 8 L37 13" fill="none" stroke="#fff" stroke-width="3.5" stroke-linecap="round"
            stroke-linejoin="round" />
    </svg>
    @if (showName()) {
      <span class="name">Deploy<b>Hub</b></span>
    }
  `,
  styles: `
    :host { display: inline-flex; align-items: center; gap: 0.55rem; }
    .mark { flex: none; }
    .name { font-weight: 800; letter-spacing: -0.02em; color: var(--dh-text); }
    .name b { font-weight: 800; color: var(--dh-primary); }
  `,
})
export class LogoComponent {
  /** Height and width of the drawing in px. */
  readonly size = input(34);
  readonly showName = input(true);
  /** Unique per logo on the page: two SVGs with the same gradient id would fight over it. */
  protected readonly gradientId = `dh-logo-gradient-${nextId++}`;
}
