import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { of, throwError } from 'rxjs';

import { AuthService } from '../../../core/services/auth/auth.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  const auth = { login: vi.fn() };

  async function render(inputs: Record<string, string> = {}) {
    auth.login.mockReset();
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideRouter([]), MessageService, { provide: AuthService, useValue: auth }],
    }).compileComponents();
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(LoginComponent);
    Object.entries(inputs).forEach(([k, v]) => fixture.componentRef.setInput(k, v));
    await fixture.whenStable();
    return fixture;
  }

  function type(el: HTMLElement, id: string, value: string) {
    const input = el.querySelector<HTMLInputElement>(`#${id}`)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function submit(fixture: Awaited<ReturnType<typeof render>>) {
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('validates before calling the API', async () => {
    const fixture = await render();
    await submit(fixture);
    expect(auth.login).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Enter a valid email address');
    expect(fixture.nativeElement.querySelector('#email').getAttribute('aria-invalid')).toBe('true');
  });

  it('shows one generic message for bad credentials and clears the password', async () => {
    const fixture = await render();
    type(fixture.nativeElement, 'email', 'alice@example.com');
    type(fixture.nativeElement, 'password', 'wrong-password-1');
    auth.login.mockReturnValue(throwError(() => ({ status: 401, title: 'Login failed', detail: 'x', fieldErrors: {} })));

    await submit(fixture);

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Invalid email or password.');
    expect(fixture.nativeElement.querySelector('#password').value).toBe('');
  });

  it('explains throttling and outages', async () => {
    const fixture = await render();
    type(fixture.nativeElement, 'email', 'alice@example.com');
    type(fixture.nativeElement, 'password', 'whatever-123');
    auth.login.mockReturnValue(
      throwError(() => ({ status: 429, title: 'Too many', detail: 'Too many failed login attempts. Try again later.', fieldErrors: {} })),
    );
    await submit(fixture);
    expect(fixture.nativeElement.textContent).toContain('Too many failed login attempts');
  });

  it('logs in and returns to the page the user wanted', async () => {
    const fixture = await render({ returnUrl: '/app/projects/4' });
    type(fixture.nativeElement, 'email', ' alice@example.com ');
    type(fixture.nativeElement, 'password', 'correct-horse-battery');
    auth.login.mockReturnValue(of({ id: 1, email: 'alice@example.com', displayName: 'Alice' }));

    await submit(fixture);

    expect(auth.login).toHaveBeenCalledWith({ email: 'alice@example.com', password: 'correct-horse-battery' });
    expect(TestBed.inject(Router).navigateByUrl).toHaveBeenCalledWith('/app/projects/4');
  });

  it('never redirects off-site after login', async () => {
    const fixture = await render({ returnUrl: 'https://evil.example' });
    type(fixture.nativeElement, 'email', 'alice@example.com');
    type(fixture.nativeElement, 'password', 'correct-horse-battery');
    auth.login.mockReturnValue(of({ id: 1, email: 'alice@example.com', displayName: 'Alice' }));
    await submit(fixture);
    expect(TestBed.inject(Router).navigateByUrl).toHaveBeenCalledWith('/app/dashboard');
  });

  it('tells the user when their session expired', async () => {
    const fixture = await render({ reason: 'expired' });
    expect(fixture.nativeElement.textContent).toContain('Your session has ended');
  });
});
