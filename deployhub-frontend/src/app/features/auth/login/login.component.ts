import { Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';

import { AssistantChatComponent } from '../../../core/components/assistant/assistant-chat.component';
import { AuthBackdropComponent } from '../../../core/components/shared/auth-backdrop.component';
import { ApiError } from '../../../core/models/api.models';
import { AuthService } from '../../../core/services/auth/auth.service';
import { safeReturnUrl } from '../../../core/utils/safe-return-url';
import { authErrorMessage } from '../auth-errors';
import { LogoComponent } from '../../../core/components/shared/logo.component';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink, ButtonModule, InputTextModule, MessageModule, AssistantChatComponent, AuthBackdropComponent, LogoComponent],
  templateUrl: './login.component.html',
})
export class LoginComponent {
  /** Query params, bound by withComponentInputBinding(). */
  readonly returnUrl = input<string>();
  readonly reason = input<string>();

  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly messages = inject(MessageService);

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly showPassword = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    password: ['', [Validators.required, Validators.maxLength(64)]],
  });

  protected showError(field: 'email' | 'password'): boolean {
    const control = this.form.controls[field];
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    this.error.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    const { email, password } = this.form.getRawValue();
    this.auth.login({ email: email.trim(), password }).subscribe({
      next: (user) => {
        this.messages.add({ severity: 'success', summary: `Welcome back, ${user.displayName}` });
        this.router.navigateByUrl(safeReturnUrl(this.returnUrl()));
      },
      error: (err: ApiError) => {
        this.submitting.set(false);
        this.form.controls.password.reset();
        this.error.set(authErrorMessage(err));
      },
    });
  }
}
