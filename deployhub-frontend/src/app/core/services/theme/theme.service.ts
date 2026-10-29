import { DOCUMENT } from '@angular/common';
import { Injectable, inject, signal } from '@angular/core';

export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'dh-theme';
const DARK_CLASS = 'app-dark';

/**
 * Light (white and orange) or dark. The choice is a per-device convenience kept in localStorage; index.html applies
 * it before the app starts. Storage may be blocked (private windows), so every access is guarded and light is the
 * fallback.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  readonly theme = signal<Theme>(this.read());

  constructor() {
    this.apply(this.theme());
  }

  toggle(): void {
    this.set(this.theme() === 'dark' ? 'light' : 'dark');
  }

  set(theme: Theme): void {
    this.theme.set(theme);
    this.apply(theme);
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      // storage blocked: the choice lasts until the page is reloaded
    }
  }

  private read(): Theme {
    try {
      return localStorage.getItem(STORAGE_KEY) === 'dark' ? 'dark' : 'light';
    } catch {
      return 'light';
    }
  }

  private apply(theme: Theme): void {
    this.document.documentElement.classList.toggle(DARK_CLASS, theme === 'dark');
  }
}
