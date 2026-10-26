import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AuthService } from '../../../core/services/auth/auth.service';
import { ForgotPasswordComponent } from './forgot-password.component';

describe('ForgotPasswordComponent', () => {
  const auth = { forgotPassword: vi.fn(), resetPassword: vi.fn() };

  async function render() {
    auth.forgotPassword.mockReset();
    auth.resetPassword.mockReset();
    await TestBed.configureTestingModule({
      imports: [ForgotPasswordComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    }).compileComponents();
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(ForgotPasswordComponent);
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

  async function reachCodeStep() {
    const fixture = await render();
    auth.forgotPassword.mockReturnValue(of(undefined));
    type(fixture.nativeElement, 'email', ' alice@example.com ');
    await submit(fixture);
    return fixture;
  }

  function fillReset(el: HTMLElement, code: string, password: string, confirm = password) {
    type(el, 'code', code);
    type(el, 'newPassword', password);
    type(el, 'confirmPassword', confirm);
  }

  it('validates the email before asking for a code', async () => {
    const fixture = await render();
    type(fixture.nativeElement, 'email', 'not-an-email');
    await submit(fixture);
    expect(auth.forgotPassword).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Enter a valid email address');
  });

  it('sends the code and moves to the code step without revealing whether the account exists', async () => {
    const fixture = await reachCodeStep();
    expect(auth.forgotPassword).toHaveBeenCalledWith('alice@example.com');
    expect(fixture.nativeElement.textContent).toContain('If alice@example.com has a DeployHub account');
    expect(fixture.nativeElement.querySelector('#code')).not.toBeNull();
  });

  it('shows throttling on the email step', async () => {
    const fixture = await render();
    auth.forgotPassword.mockReturnValue(
      throwError(() => ({ status: 429, title: 'Too many', detail: 'Too many reset codes requested. Try again later.', fieldErrors: {} })),
    );
    type(fixture.nativeElement, 'email', 'alice@example.com');
    await submit(fixture);
    expect(fixture.nativeElement.textContent).toContain('Too many reset codes requested');
  });

  it('checks the code format and that both passwords match', async () => {
    const fixture = await reachCodeStep();
    fillReset(fixture.nativeElement, '12ab', 'brand-new-passphrase', 'something-else-entirely');
    await submit(fixture);
    expect(auth.resetPassword).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Enter the 6-digit code from the email');
    expect(fixture.nativeElement.textContent).toContain("Passwords don't match");
  });

  it('resets the password and sends the user to log in', async () => {
    const fixture = await reachCodeStep();
    auth.resetPassword.mockReturnValue(of(undefined));
    fillReset(fixture.nativeElement, ' 123456 ', 'brand-new-passphrase');
    await submit(fixture);
    expect(auth.resetPassword).toHaveBeenCalledWith({
      email: 'alice@example.com',
      code: '123456',
      newPassword: 'brand-new-passphrase',
    });
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/login'], { queryParams: { reason: 'reset' } });
  });

  it('shows a wrong or expired code next to the code field', async () => {
    const fixture = await reachCodeStep();
    auth.resetPassword.mockReturnValue(
      throwError(() => ({
        status: 400,
        title: 'Invalid',
        detail: 'x',
        fieldErrors: { code: 'The code is wrong or has expired. Check the email or request a new code.' },
      })),
    );
    fillReset(fixture.nativeElement, '654321', 'brand-new-passphrase');
    await submit(fixture);
    expect(fixture.nativeElement.querySelector('#code-error').textContent).toContain('wrong or has expired');
  });
});
