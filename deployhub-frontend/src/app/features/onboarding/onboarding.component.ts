import { Component, ElementRef, inject, input, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FormControl } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import { ButtonModule } from 'primeng/button';

import { AssistantAvatarComponent } from '../../core/components/assistant/assistant-avatar.component';
import { ApiError, ChatTurn, Occupation, StudentVerification } from '../../core/models/api.models';
import { AssistantApiService } from '../../core/services/api/assistant-api.service';
import { BillingApiService } from '../../core/services/api/billing-api.service';
import { AuthService } from '../../core/services/auth/auth.service';
import { formatPrice } from '../../core/utils/money';
import { safeReturnUrl } from '../../core/utils/safe-return-url';
import { passwordPolicy } from '../auth/signup/signup.component';

/** Where the conversation is. Each step decides what the input area shows. */
export type Step = 'name' | 'email' | 'confirmEmail' | 'code' | 'occupation' | 'password' | 'creating' | 'student'
  | 'done';

interface Message {
  from: 'bot' | 'user';
  text: string;
  notice?: string | null;
}

const QUESTIONS: Partial<Record<Step, string>> = {
  name: "What's your name?",
  email: "What's your email address?",
  code: 'Type the 6-digit code from the email.',
  occupation: 'Are you a student or a working professional?',
  password: 'Last step: choose a password (10 or more characters). I never see it; it goes straight to DeployHub.',
};

/**
 * Sign-up as a conversation with the DeployHub assistant: name, email (proven with a code), student or working
 * professional, password, and for students an optional student ID check. A "Your details" card on the side fills in
 * as answers are accepted. Answers are checked by DeployHub itself (no AI needed); a message ending in "?" is a
 * question for the assistant instead. The password is typed in a normal hidden field and never sent to the AI.
 */import { LogoComponent } from '../../core/components/shared/logo.component';

import { AuthBackdropComponent } from '../../core/components/shared/auth-backdrop.component';

@Component({
  selector: 'app-onboarding',
  imports: [FormsModule, RouterLink, ButtonModule, LogoComponent, AuthBackdropComponent, AssistantAvatarComponent],
  templateUrl: './onboarding.component.html',
  styleUrl: './onboarding.component.scss',
})
export class OnboardingComponent {
  readonly returnUrl = input<string>();

  private readonly api = inject(AssistantApiService);
  private readonly auth = inject(AuthService);
  private readonly billing = inject(BillingApiService);
  private readonly router = inject(Router);
  private readonly list = viewChild<ElementRef<HTMLElement>>('list');

  protected readonly step = signal<Step>('name');
  protected readonly busy = signal(false);
  protected readonly messages = signal<Message[]>([
    { from: 'bot', text: "Hi! I'm the DeployHub assistant. I'll set up your account in a minute. Ask me anything "
      + 'along the way: just end your message with a question mark.' },
    { from: 'bot', text: QUESTIONS.name! },
  ]);

  // What the "Your details" card shows.
  protected readonly name = signal('');
  protected readonly email = signal('');
  protected readonly emailVerified = signal(false);
  protected readonly occupation = signal<Occupation | null>(null);
  protected readonly passwordSet = signal(false);
  protected readonly student = signal<StudentVerification | null>(null);

  /** The typo suggestion waiting for the user's choice (e.g. sajal@gmail.com for sajal@gmial.com). */
  protected readonly suggestion = signal<string | null>(null);
  protected readonly emailTaken = signal(false);

  protected answer = '';
  protected password = '';
  protected confirmPassword = '';
  protected readonly passwordError = signal<string | null>(null);
  private proof = '';
  private discountText = 'a discount';

  protected send(): void {
    const text = this.answer.trim();
    if (!text || this.busy()) return;
    this.answer = '';
    this.say('user', this.step() === 'code' ? text.replace(/\s/g, '') : text);
    if (text.endsWith('?')) {
      this.askAssistant(text);
      return;
    }
    switch (this.step()) {
      case 'name':
        return this.checkName(text);
      case 'email':
        return this.checkEmail(text);
      case 'code':
        return this.verifyCode(text.replace(/\s/g, ''));
    }
  }

  private checkName(value: string): void {
    this.busy.set(true);
    this.api.checkSignup('NAME', value).subscribe({
      next: (check) => {
        this.busy.set(false);
        this.say('bot', check.message);
        if (check.ok) {
          this.name.set(check.value);
          this.step.set('email');
        }
      },
      error: (err: ApiError) => this.failed(err),
    });
  }

  private checkEmail(value: string): void {
    this.busy.set(true);
    this.emailTaken.set(false);
    this.api.checkSignup('EMAIL', value).subscribe({
      next: (check) => {
        this.busy.set(false);
        if (!check.ok) {
          this.say('bot', check.message);
          return;
        }
        this.email.set(check.value);
        if (check.suggestion) {
          this.suggestion.set(check.suggestion);
          this.say('bot', check.message);
          this.step.set('confirmEmail');
          return;
        }
        this.sendCode();
      },
      error: (err: ApiError) => this.failed(err),
    });
  }

  /** The user's answer to "Did you mean ...?". */
  protected chooseEmail(useSuggestion: boolean): void {
    const chosen = useSuggestion ? this.suggestion()! : this.email();
    this.say('user', useSuggestion ? `Yes, ${chosen}` : `No, keep ${chosen}`);
    this.suggestion.set(null);
    this.email.set(chosen);
    this.sendCode();
  }

  protected sendCode(): void {
    this.busy.set(true);
    this.api.sendSignupCode(this.email()).subscribe({
      next: () => {
        this.busy.set(false);
        this.say('bot', `I've sent a 6-digit code to ${this.email()}. ${QUESTIONS.code}`);
        this.step.set('code');
      },
      error: (err: ApiError) => {
        this.busy.set(false);
        if (err.status === 409) {
          this.emailTaken.set(true);
          this.say('bot', err.detail);
          this.say('bot', 'Or type a different email address.');
          this.step.set('email');
          return;
        }
        this.failed(err);
        this.step.set('email');
      },
    });
  }

  protected changeEmail(): void {
    this.emailVerified.set(false);
    this.email.set('');
    this.say('bot', QUESTIONS.email!);
    this.step.set('email');
  }

  private verifyCode(code: string): void {
    this.busy.set(true);
    this.api.verifySignupCode(this.email(), code).subscribe({
      next: (proof) => {
        this.busy.set(false);
        this.proof = proof.proof;
        this.emailVerified.set(true);
        this.say('bot', `Your email is confirmed. ${QUESTIONS.occupation}`);
        this.step.set('occupation');
      },
      error: (err: ApiError) => this.failed(err),
    });
  }

  protected chooseOccupation(occupation: Occupation): void {
    this.occupation.set(occupation);
    this.say('user', occupation === 'STUDENT' ? "I'm a student" : "I'm a working professional");
    this.say('bot', (occupation === 'STUDENT' ? 'Great, students get a discount on paid deploys. ' : 'Great. ')
      + QUESTIONS.password);
    this.step.set('password');
  }

  protected createAccount(): void {
    this.passwordError.set(null);
    const policy = passwordPolicy(new FormControl(this.password))?.['policy'] as string | undefined;
    if (!this.password) {
      this.passwordError.set('Please choose a password.');
      return;
    }
    if (policy) {
      this.passwordError.set(policy);
      return;
    }
    if (this.password.trim().toLowerCase() === this.email()) {
      this.passwordError.set("Don't use your email as the password.");
      return;
    }
    if (this.password !== this.confirmPassword) {
      this.passwordError.set("The two passwords don't match.");
      return;
    }
    this.step.set('creating');
    this.auth
      .register({
        displayName: this.name(),
        email: this.email(),
        password: this.password,
        occupation: this.occupation() ?? undefined,
        emailProof: this.proof,
      })
      .subscribe({
        next: () => {
          this.password = '';
          this.confirmPassword = '';
          this.passwordSet.set(true);
          if (this.occupation() === 'STUDENT') {
            this.offerStudentCheck();
          } else {
            this.finish();
          }
        },
        error: (err: ApiError) => {
          this.step.set('password');
          if (err.fieldErrors['emailProof']) {
            this.say('bot', err.fieldErrors['emailProof']);
            this.emailVerified.set(false);
            this.step.set('email');
            this.say('bot', QUESTIONS.email!);
            return;
          }
          if (err.status === 409) {
            this.say('bot', 'This email already has an account. Log in instead, or use Forgot password.');
            this.emailTaken.set(true);
            return;
          }
          this.passwordError.set(err.fieldErrors['password'] ?? err.detail ?? 'Signing up failed. Please try again.');
        },
      });
  }

  private offerStudentCheck(): void {
    forkJoin({
      summary: this.billing.summary().pipe(catchError(() => of(null))),
      status: this.api.status().pipe(catchError(() => of(null))),
    }).subscribe(({ summary, status }) => {
      if (summary && !summary.enabled) {
        // Paid deploys are switched off: nothing to discount, so no student check now.
        this.say('bot', 'Your account is ready! Deploys are free on DeployHub right now, so there is nothing to pay. '
          + 'When paid deploys start, students can verify in Settings > Student discount.');
        this.finish();
        return;
      }
      if (summary?.studentDiscount) {
        this.discountText = `${formatPrice(summary.studentDiscount, summary.currency)} off`;
      }
      if (status?.idCardCheck) {
        this.askForStudentCard();
        return;
      }
      // No photo check on this DeployHub (no AI key, or a free AI tier): the college email way is in Settings.
      this.say('bot', `Your account is ready! As a student you get ${this.discountText} every paid deploy. To unlock it, `
        + 'confirm your college email (for example name@college.ac.in) in Settings > Student discount.');
      this.finish();
    });
  }

  private askForStudentCard(): void {
    this.say('bot', `Your account is ready! As a student you get ${this.discountText} every paid deploy. `
      + 'Upload a photo of your student ID card to unlock it. The photo is checked once and deleted right away. '
      + 'You can also skip this and do it later in Settings (there you can use your college email instead).');
    this.step.set('student');
  }

  protected uploadCard(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    (event.target as HTMLInputElement).value = '';
    if (!file) return;
    this.say('user', `Uploaded ${file.name}`);
    this.busy.set(true);
    this.api.verifyStudentIdCard(file).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.student.set(result);
        this.say('bot', result.message);
        this.auth.refresh().subscribe();
        this.finish();
      },
      error: (err: ApiError) => {
        this.busy.set(false);
        this.say('bot', (err.fieldErrors['file'] ?? err.detail ?? 'The check failed.')
          + ' Try another photo, or skip for now.');
      },
    });
  }

  protected skipStudentCheck(): void {
    this.say('user', 'Later');
    this.say('bot', 'No problem: Settings > Student discount, whenever you like.');
    this.finish();
  }

  private finish(): void {
    this.say('bot', `All set, ${this.name().split(/\s+/)[0]}! Shall we deploy your first project? I'll stay in the `
      + 'corner of every page if you need help.');
    this.step.set('done');
  }

  protected goTo(url: string): void {
    this.router.navigateByUrl(url === 'return' ? safeReturnUrl(this.returnUrl()) : url);
  }

  private askAssistant(question: string): void {
    this.busy.set(true);
    const history: ChatTurn[] = this.messages().map((m) => ({
      role: m.from === 'user' ? 'USER' : 'ASSISTANT',
      text: m.text.slice(0, 2000),
    }));
    this.api.chat('signup', history).subscribe({
      next: (reply) => {
        this.busy.set(false);
        this.say('bot', reply.text, reply.notice);
        const again = QUESTIONS[this.step()];
        if (again) this.say('bot', `Back to sign-up: ${again}`);
      },
      error: (err: ApiError) => this.failed(err),
    });
  }

  private failed(err: ApiError): void {
    this.busy.set(false);
    const field = Object.values(err.fieldErrors ?? {})[0];
    this.say('bot', field ?? (err.status === 0 ? "I can't reach DeployHub right now. Please try again."
      : err.detail || 'Something went wrong. Please try again.'));
  }

  private say(from: 'bot' | 'user', text: string, notice?: string | null): void {
    this.messages.update((all) => [...all, { from, text, notice }]);
    setTimeout(() => {
      const el = this.list()?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }
}
