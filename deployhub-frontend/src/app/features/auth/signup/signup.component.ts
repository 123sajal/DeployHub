import { Component, inject, input, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';

import { ApiError } from '../../../core/models/api.models';
import { AuthService } from '../../../core/services/auth/auth.service';
import { safeReturnUrl } from '../../../core/utils/safe-return-url';
import { authErrorMessage } from '../auth-errors';

/** Mirrors the backend's @ValidPassword: 10-64 characters, at most 72 UTF-8 bytes, not only spaces. */
export function passwordPolicy(control: AbstractControl): ValidationErrors | null {
  const value: string = control.value ?? '';
  if (!value) return null;
  const length = [...value].length;
  if (length < 10) return { policy: 'Use at least 10 characters' };
  if (length > 64) return { policy: 'Use at most 64 characters' };
  if (new TextEncoder().encode(value).length > 72) return { policy: 'Too long (fewer special characters, please)' };
  if (!value.trim()) return { policy: 'Password cannot be only spaces' };
  return null;
}

function passwordsMatch(group: AbstractControl): ValidationErrors | null {
  const password = group.get('password')?.value;
  const confirm = group.get('confirmPassword')?.value;
  return confirm && password !== confirm ? { mismatch: true } : null;
}

@Component({
  selector: 'app-signup',
  imports: [ReactiveFormsModule, RouterLink, ButtonModule, InputTextModule, MessageModule],
  templateUrl: './signup.component.html',
})
export class SignupComponent {
  readonly returnUrl = input<string>();

  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly messages = inject(MessageService);

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly emailTaken = signal(false);
  protected readonly showPassword = signal(false);

  protected readonly form = this.fb.nonNullable.group(
    {
      displayName: ['', [Validators.required, Validators.maxLength(100)]],
      email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
      password: ['', [Validators.required, passwordPolicy]],
      confirmPassword: ['', [Validators.required]],
    },
    { validators: passwordsMatch },
  );

  protected showError(field: keyof typeof this.form.controls): boolean {
    const control = this.form.controls[field];
    return control.invalid && (control.touched || control.dirty);
  }

  protected errorText(field: keyof typeof this.form.controls): string {
    const errors = this.form.controls[field].errors ?? {};
    if (errors['server']) return errors['server'];
    if (errors['required']) return 'This field is required';
    if (errors['email']) return 'Enter a valid email address';
    if (errors['maxlength']) return `Must be at most ${errors['maxlength'].requiredLength} characters`;
    if (errors['policy']) return errors['policy'];
    return 'Invalid value';
  }

  protected get mismatch(): boolean {
    const confirm = this.form.controls.confirmPassword;
    return this.form.hasError('mismatch') && (confirm.touched || confirm.dirty);
  }

  protected submit(): void {
    this.error.set(null);
    this.emailTaken.set(false);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    const { displayName, email, password } = this.form.getRawValue();
    this.auth.register({ displayName: displayName.trim(), email: email.trim(), password }).subscribe({
      next: (user) => {
        this.messages.add({ severity: 'success', summary: `Welcome to DeployHub, ${user.displayName}` });
        this.router.navigateByUrl(safeReturnUrl(this.returnUrl()));
      },
      error: (err: ApiError) => {
        this.submitting.set(false);
        if (err.status === 409) {
          this.emailTaken.set(true);
          this.form.controls.email.setErrors({ server: 'An account with this email already exists' });
          return;
        }
        let mapped = false;
        for (const [field, message] of Object.entries(err.fieldErrors)) {
          const control = this.form.get(field);
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
