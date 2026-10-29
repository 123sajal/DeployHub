import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AssistantApiService } from '../../core/services/api/assistant-api.service';
import { BillingApiService } from '../../core/services/api/billing-api.service';
import { AuthService } from '../../core/services/auth/auth.service';
import { OnboardingComponent } from './onboarding.component';

describe('OnboardingComponent', () => {
  const api = {
    checkSignup: vi.fn(),
    sendSignupCode: vi.fn(),
    verifySignupCode: vi.fn(),
    chat: vi.fn(),
    verifyStudentIdCard: vi.fn(),
    status: vi.fn(),
  };
  const auth = { register: vi.fn(), refresh: vi.fn(() => of(null)) };
  const billing = { summary: vi.fn() };

  async function render() {
    Object.values(api).forEach((f) => f.mockReset());
    auth.register.mockReset();
    billing.summary.mockReset();
    await TestBed.configureTestingModule({
      imports: [OnboardingComponent],
      providers: [
        provideRouter([]),
        { provide: AssistantApiService, useValue: api },
        { provide: AuthService, useValue: auth },
        { provide: BillingApiService, useValue: billing },
      ],
    }).compileComponents();
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(OnboardingComponent);
    await fixture.whenStable();
    return fixture;
  }

  async function type(fixture: Awaited<ReturnType<typeof render>>, text: string) {
    const input = fixture.nativeElement.querySelector('#ob-answer') as HTMLInputElement;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    fixture.nativeElement.querySelector('form.text').dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  function click(fixture: Awaited<ReturnType<typeof render>>, label: string) {
    const button = [...fixture.nativeElement.querySelectorAll('button')].find((b) =>
      (b as HTMLElement).textContent?.includes(label),
    ) as HTMLButtonElement;
    expect(button).toBeTruthy();
    button.click();
  }

  const text = (fixture: Awaited<ReturnType<typeof render>>) => fixture.nativeElement.textContent as string;

  it('walks through sign-up and fills in the details card', async () => {
    const fixture = await render();
    api.checkSignup
      .mockReturnValueOnce(of({ ok: true, value: 'Sajal', message: 'Nice to meet you, Sajal!', suggestion: null }))
      .mockReturnValueOnce(of({ ok: true, value: 'sajal@gmail.com', message: 'Thanks!', suggestion: null }));
    api.sendSignupCode.mockReturnValue(of(undefined));
    api.verifySignupCode.mockReturnValue(of({ email: 'sajal@gmail.com', proof: 'p-1', expiresAt: '' }));
    auth.register.mockReturnValue(of({ id: 1, email: 'sajal@gmail.com', displayName: 'Sajal' }));

    await type(fixture, 'Sajal');
    expect(text(fixture)).toContain('Nice to meet you, Sajal!');
    await type(fixture, 'sajal@gmail.com');
    expect(api.sendSignupCode).toHaveBeenCalledWith('sajal@gmail.com');
    expect(text(fixture)).toContain("I've sent a 6-digit code to sajal@gmail.com");
    await type(fixture, '123 456');
    expect(api.verifySignupCode).toHaveBeenCalledWith('sajal@gmail.com', '123456');
    expect(text(fixture)).toContain('Your email is confirmed');

    click(fixture, "I'm a working professional");
    await fixture.whenStable();
    const [pw, confirm] = [
      fixture.nativeElement.querySelector('#ob-password') as HTMLInputElement,
      fixture.nativeElement.querySelector('#ob-confirm') as HTMLInputElement,
    ];
    pw.value = 'correct-horse-battery';
    pw.dispatchEvent(new Event('input'));
    confirm.value = 'correct-horse-battery';
    confirm.dispatchEvent(new Event('input'));
    fixture.nativeElement.querySelector('form.password').dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(auth.register).toHaveBeenCalledWith({
      displayName: 'Sajal',
      email: 'sajal@gmail.com',
      password: 'correct-horse-battery',
      occupation: 'PROFESSIONAL',
      emailProof: 'p-1',
    });
    expect(text(fixture)).toContain('All set, Sajal!');
    expect(text(fixture)).toContain('Working professional');
    expect(text(fixture)).toContain('Deploy my first project');
    // The password never ends up in the conversation (which is what the assistant sees).
    expect(text(fixture)).not.toContain('correct-horse-battery');
  });

  it('offers the typo suggestion and lets the user keep their own address', async () => {
    const fixture = await render();
    api.checkSignup
      .mockReturnValueOnce(of({ ok: true, value: 'A', message: 'Hi A', suggestion: null }))
      .mockReturnValueOnce(of({ ok: true, value: 'a@gmial.com', message: 'Did you mean a@gmail.com?', suggestion: 'a@gmail.com' }));
    api.sendSignupCode.mockReturnValue(of(undefined));
    await type(fixture, 'A');
    await type(fixture, 'a@gmial.com');
    expect(text(fixture)).toContain('Did you mean a@gmail.com?');
    expect(api.sendSignupCode).not.toHaveBeenCalled();

    click(fixture, 'Yes, use a@gmail.com');
    await fixture.whenStable();
    expect(api.sendSignupCode).toHaveBeenCalledWith('a@gmail.com');
  });

  it('tells an existing user to log in instead', async () => {
    const fixture = await render();
    api.checkSignup
      .mockReturnValueOnce(of({ ok: true, value: 'A', message: 'Hi A', suggestion: null }))
      .mockReturnValueOnce(of({ ok: true, value: 'a@x.com', message: 'Thanks', suggestion: null }));
    api.sendSignupCode.mockReturnValue(
      throwError(() => ({ status: 409, title: 'Conflict', detail: 'This email already has a DeployHub account.', fieldErrors: {} })),
    );
    await type(fixture, 'A');
    await type(fixture, 'a@x.com');
    expect(text(fixture)).toContain('This email already has a DeployHub account.');
    expect(fixture.nativeElement.querySelector('a[href="/login"]')).toBeTruthy();
  });

  it('sends a message ending in a question mark to the assistant and then asks the step again', async () => {
    const fixture = await render();
    api.chat.mockReturnValue(of({ text: 'Your email is used to log in.', source: 'AI', notice: null, saved: false }));
    await type(fixture, 'Why do you need my email?');
    expect(api.checkSignup).not.toHaveBeenCalled();
    expect(api.chat).toHaveBeenCalledWith('signup', expect.arrayContaining([{ role: 'USER', text: 'Why do you need my email?' }]));
    expect(text(fixture)).toContain('Your email is used to log in.');
    expect(text(fixture)).toContain("Back to sign-up: What's your name?");
  });

  it('sends a student to the college email in Settings when the photo check is off', async () => {
    const fixture = await render();
    api.status.mockReturnValue(of({ aiAvailable: true, idCardCheck: false }));
    billing.summary.mockReturnValue(of({ enabled: true, studentDiscount: 500, currency: 'INR' }));
    auth.register.mockReturnValue(of({ id: 1, email: 'a@x.com', displayName: 'Asha' }));
    const component = fixture.componentInstance as unknown as {
      name: { set(v: string): void };
      email: { set(v: string): void };
      occupation: { set(v: string): void };
      step: { set(v: string): void };
    };
    component.name.set('Asha');
    component.email.set('a@x.com');
    component.occupation.set('STUDENT');
    component.step.set('password');
    await fixture.whenStable();
    for (const id of ['ob-password', 'ob-confirm']) {
      const input = fixture.nativeElement.querySelector(`#${id}`) as HTMLInputElement;
      input.value = 'correct-horse-battery';
      input.dispatchEvent(new Event('input'));
    }
    fixture.nativeElement.querySelector('form.password').dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    expect(text(fixture)).toContain('confirm your college email');
    expect(text(fixture)).toContain('₹5 off');
    expect(fixture.nativeElement.querySelector('input[type=file]')).toBeNull();
    expect(api.verifyStudentIdCard).not.toHaveBeenCalled();
  });

  it('skips the student check while deploys are free', async () => {
    const fixture = await render();
    api.status.mockReturnValue(of({ aiAvailable: true, idCardCheck: true }));
    billing.summary.mockReturnValue(of({ enabled: false, studentDiscount: 500, currency: 'INR' }));
    auth.register.mockReturnValue(of({ id: 1, email: 'a@x.com', displayName: 'Asha' }));
    const component = fixture.componentInstance as unknown as {
      name: { set(v: string): void };
      email: { set(v: string): void };
      occupation: { set(v: string): void };
      step: { set(v: string): void };
    };
    component.name.set('Asha');
    component.email.set('a@x.com');
    component.occupation.set('STUDENT');
    component.step.set('password');
    await fixture.whenStable();
    for (const id of ['ob-password', 'ob-confirm']) {
      const input = fixture.nativeElement.querySelector(`#${id}`) as HTMLInputElement;
      input.value = 'correct-horse-battery';
      input.dispatchEvent(new Event('input'));
    }
    fixture.nativeElement.querySelector('form.password').dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    expect(text(fixture)).toContain('Deploys are free on DeployHub right now');
    expect(fixture.nativeElement.querySelector('input[type=file]')).toBeNull();
  });

  it('checks the password locally before creating the account', async () => {
    const fixture = await render();
    const component = fixture.componentInstance as unknown as Record<string, unknown> & {
      step: { set(v: string): void };
    };
    component.step.set('password');
    await fixture.whenStable();
    const pw = fixture.nativeElement.querySelector('#ob-password') as HTMLInputElement;
    pw.value = 'short';
    pw.dispatchEvent(new Event('input'));
    fixture.nativeElement.querySelector('form.password').dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    expect(text(fixture)).toContain('at least 10');
    expect(auth.register).not.toHaveBeenCalled();
  });
});
