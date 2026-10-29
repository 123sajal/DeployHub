import { Component, input } from '@angular/core';

let nextId = 0;

/**
 * The DeployHub assistant's face: a smiling robot. "color" is an orange tile with a white face (chat, tips);
 * "light" is a white tile with an orange face, for use on orange buttons. Decoration only.
 */
@Component({
  selector: 'app-assistant-avatar',
  template: `
    <svg [attr.width]="size()" [attr.height]="size()" viewBox="0 0 64 64" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient [attr.id]="gradientId" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#fb923c" />
          <stop offset="1" stop-color="#cc4a0a" />
        </linearGradient>
      </defs>
      <rect x="4" y="4" width="56" height="56" rx="18" [attr.fill]="variant() === 'light' ? '#ffffff' : tile" />
      <path d="M32 9 V15" [attr.stroke]="variant() === 'light' ? '#cc4a0a' : '#ffffff'" stroke-width="3.5"
            stroke-linecap="round" />
      <circle cx="32" cy="8.5" r="3.5" [attr.fill]="variant() === 'light' ? '#cc4a0a' : '#ffffff'" />
      <rect x="13" y="17" width="38" height="32" rx="12" [attr.fill]="variant() === 'light' ? tile : '#ffffff'" />
      <circle cx="24.5" cy="31" r="4" [attr.fill]="variant() === 'light' ? '#ffffff' : '#cc4a0a'" />
      <circle cx="39.5" cy="31" r="4" [attr.fill]="variant() === 'light' ? '#ffffff' : '#cc4a0a'" />
      <path d="M25 40 Q32 45 39 40" fill="none" [attr.stroke]="variant() === 'light' ? '#ffffff' : '#cc4a0a'"
            stroke-width="3" stroke-linecap="round" />
    </svg>
  `,
  styles: `:host { display: inline-flex; flex: none; } svg { display: block; }`,
})
export class AssistantAvatarComponent {
  readonly size = input(30);
  readonly variant = input<'color' | 'light'>('color');
  /** Unique per avatar on the page (several are shown in one chat). */
  protected readonly gradientId = `dh-assistant-gradient-${nextId++}`;
  protected readonly tile = `url(#${this.gradientId})`;
}
