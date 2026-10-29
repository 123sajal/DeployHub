import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';

import { ApiError } from '../../../core/models/api.models';
import { AuthService } from '../../../core/services/auth/auth.service';
import { authErrorMessage } from '../auth-errors';
import { passwordPolicy } from '../signup/signup.component';

function passwordsMatch(group: AbstractControl): ValidationErrors | null {
  const password = group.get('newPassword')?.value;
  const confirm = group.get('confirmPassword')?.value;
  return confirm && password !== confirm ? { mismatch: true } : null;
}

/**
 * Forgot password in two steps: 1. enter the email and get a 6-digit code by email,
 * 2. enter the code and a new password. Afterwards the user logs in with the new password.
 */import { LogoComponent } from '../../../core/components/shared/logo.component';

import { AuthBackdropComponent } from '../../../core/components/shared/auth-backdrop.component';

@Component({
  selector: 'app-forgot-password',
  imports: [ReactiveFormsModule, RouterLink, ButtonModule, InputTextModule, MessageModule, LogoComponent, AuthBackdropComponent],
  templateUrl: './forgot-password.component.html',
})
export class ForgotPasswordComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly step = signal<'email' | 'code'>('email');
  protected readonly submitting = signal(false);
  protected readonly resending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);
  protected readonly showPassword = signal(false);

  protected readonly emailForm = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
  });

  protected readonly resetForm = this.fb.nonNullable.group(
    {
      code: ['', [Validators.required, Validators.pattern(/^\s*\d{6}\s*$/)]],
      newPassword: ['', [Validators.required, passwordPolicy]],
      confirmPassword: ['', [Validators.required]],
    },
    { validators: passwordsMatch },
  );

  protected get email(): string {
    return this.emailForm.controls.email.value.trim();
  }

  protected showError(control: AbstractControl): boolean {
    return control.invalid && (control.touched || control.dirty);
  }

  protected errorText(control: AbstractControl): string {
    const errors = control.errors ?? {};
    if (errors['server']) return errors['server'];
    if (errors['required']) return 'This field is required';
    if (errors['email']) return 'Enter a valid email address';
    if (errors['pattern']) return 'Enter the 6-digit code from the email';
    if (errors['policy']) return errors['policy'];
    return 'Invalid value';
  }

  protected get mismatch(): boolean {
    const confirm = this.resetForm.controls.confirmPassword;
    return this.resetForm.hasError('mismatch') && (confirm.touched || confirm.dirty);
  }

  protected sendCode(): void {
    this.error.set(null);
    if (this.emailForm.invalid) {
      this.emailForm.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.auth.forgotPassword(this.email).subscribe({
      next: () => {
        this.submitting.set(false);
        this.step.set('code');
        this.info.set(`If ${this.email} has a DeployHub account, a 6-digit code is on its way. It expires in 10 minutes.`);
      },
      error: (err: ApiError) => {
        this.submitting.set(false);
        this.error.set(authErrorMessage(err));
      },
    });
  }

  protected resend(): void {
    this.error.set(null);
    this.resending.set(true);
    this.auth.forgotPassword(this.email).subscribe({
      next: () => {
        this.resending.set(false);
        this.resetForm.controls.code.reset();
        this.info.set('A new code has been sent. Only the newest code works.');
      },
      error: (err: ApiError) => {
        this.resending.set(false);
        this.error.set(authErrorMessage(err));
      },
    });
  }

  protected changeEmail(): void {
    this.error.set(null);
    this.info.set(null);
    this.resetForm.reset();
    this.step.set('email');
  }

  protected resetPassword(): void {
    this.error.set(null);
    if (this.resetForm.invalid) {
      this.resetForm.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    const { code, newPassword } = this.resetForm.getRawValue();
    this.auth.resetPassword({ email: this.email, code: code.trim(), newPassword }).subscribe({
      next: () => this.router.navigate(['/login'], { queryParams: { reason: 'reset' } }),
      error: (err: ApiError) => {
        this.submitting.set(false);
        let mapped = false;
        for (const [field, message] of Object.entries(err.fieldErrors ?? {})) {
          const control = this.resetForm.get(field);
          if (control) {
            control.setErrors({ server: message });
            control.markAsTouched();
            mapped = true;
          }
        }
        if (!mapped) this.error.set(authErrorMessage(err));
      },
    });
  }
}
