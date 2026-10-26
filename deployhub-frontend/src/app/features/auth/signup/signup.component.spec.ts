import { FormControl } from '@angular/forms';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { of, throwError } from 'rxjs';

import { AuthService } from '../../../core/services/auth/auth.service';
import { SignupComponent, passwordPolicy } from './signup.component';

describe('passwordPolicy', () => {
  it.each([
    ['short', 'at least 10'],
    ['x'.repeat(65), 'at most 64'],
    ['🔒'.repeat(20), 'Too long'],
    [' '.repeat(12), 'only spaces'],
  ])('rejects %s', (value, hint) => {
    expect(passwordPolicy(new FormControl(value))?.['policy']).toContain(hint);
  });

  it('accepts a passphrase', () => {
    expect(passwordPolicy(new FormControl('correct horse battery'))).toBeNull();
  });
});

describe('SignupComponent', () => {
  const auth = { register: vi.fn() };

  async function render() {
    auth.register.mockReset();
    await TestBed.configureTestingModule({
      imports: [SignupComponent],
      providers: [provideRouter([]), MessageService, { provide: AuthService, useValue: auth }],
    }).compileComponents();
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(SignupComponent);
    await fixture.whenStable();
    return fixture;
  }

  function fill(el: HTMLElement, values: Record<string, string>) {
    for (const [id, value] of Object.entries(values)) {
      const input = el.querySelector<HTMLInputElement>(`#${id}`)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
  }

  const VALID = {
    displayName: 'Bob',
    email: 'bob@example.com',
    password: 'correct-horse-battery',
    confirmPassword: 'correct-horse-battery',
  };

  async function submit(fixture: Awaited<ReturnType<typeof render>>) {
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('requires matching passwords', async () => {
    const fixture = await render();
    fill(fixture.nativeElement, { ...VALID, confirmPassword: 'something-else-1' });
    await submit(fixture);
    expect(auth.register).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain("Passwords don't match");
  });

  it('marks the email field when the account already exists', async () => {
    const fixture = await render();
    fill(fixture.nativeElement, VALID);
    auth.register.mockReturnValue(
      throwError(() => ({ status: 409, title: 'Conflict', detail: 'An account with this email already exists', fieldErrors: {} })),
    );
    await submit(fixture);
    expect(fixture.nativeElement.textContent).toContain('An account with this email already exists');
    expect(fixture.nativeElement.textContent).toContain('Log in instead');
  });

  it('shows server-side field errors next to the field', async () => {
    const fixture = await render();
    fill(fixture.nativeElement, VALID);
    auth.register.mockReturnValue(
      throwError(() => ({ status: 400, title: 'Validation failed', detail: 'x', fieldErrors: { password: 'must not be the same as your email' } })),
    );
    await submit(fixture);
    expect(fixture.nativeElement.textContent).toContain('must not be the same as your email');
  });

  it('creates the account without sending the confirmation field, then opens the dashboard', async () => {
    const fixture = await render();
    fill(fixture.nativeElement, VALID);
    auth.register.mockReturnValue(of({ id: 2, email: 'bob@example.com', displayName: 'Bob' }));
    await submit(fixture);
    expect(auth.register).toHaveBeenCalledWith({ displayName: 'Bob', email: 'bob@example.com', password: 'correct-horse-battery' });
    expect(TestBed.inject(Router).navigateByUrl).toHaveBeenCalledWith('/app/dashboard');
  });
});
