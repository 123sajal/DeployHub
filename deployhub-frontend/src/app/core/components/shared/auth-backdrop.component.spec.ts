import { TestBed } from '@angular/core/testing';

import { AuthBackdropComponent } from './auth-backdrop.component';

describe('AuthBackdropComponent', () => {
  it('draws the cubes as decoration only', async () => {
    await TestBed.configureTestingModule({ imports: [AuthBackdropComponent] }).compileComponents();
    const fixture = TestBed.createComponent(AuthBackdropComponent);
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.getAttribute('aria-hidden')).toBe('true');
    expect(host.querySelectorAll('.cube')).toHaveLength(7);
    expect(host.querySelectorAll('.cube .face')).toHaveLength(42);
    expect(host.querySelector('.floor')).toBeTruthy();
    expect(host.textContent?.trim()).toBe('');
  });

  it('is calmer inside the app: fewer, fainter cubes', async () => {
    await TestBed.configureTestingModule({ imports: [AuthBackdropComponent] }).compileComponents();
    const fixture = TestBed.createComponent(AuthBackdropComponent);
    fixture.componentRef.setInput('calm', true);
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.classList.contains('calm')).toBe(true);
    expect(host.querySelectorAll('.cube')).toHaveLength(4);
  });
});
