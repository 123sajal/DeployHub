import { TestBed } from '@angular/core/testing';

import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  beforeEach(() => {
    localStorage.removeItem('dh-theme');
    document.documentElement.classList.remove('app-dark');
    TestBed.resetTestingModule();
  });

  it('starts light, switches to dark and remembers it', () => {
    const service = TestBed.inject(ThemeService);
    expect(service.theme()).toBe('light');
    expect(document.documentElement.classList.contains('app-dark')).toBe(false);

    service.toggle();
    expect(service.theme()).toBe('dark');
    expect(document.documentElement.classList.contains('app-dark')).toBe(true);
    expect(localStorage.getItem('dh-theme')).toBe('dark');

    service.toggle();
    expect(document.documentElement.classList.contains('app-dark')).toBe(false);
    expect(localStorage.getItem('dh-theme')).toBe('light');
  });

  it('uses the saved choice', () => {
    localStorage.setItem('dh-theme', 'dark');
    expect(TestBed.inject(ThemeService).theme()).toBe('dark');
    expect(document.documentElement.classList.contains('app-dark')).toBe(true);
  });
});
