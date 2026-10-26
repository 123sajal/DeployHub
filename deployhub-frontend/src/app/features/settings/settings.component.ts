import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { TagModule } from 'primeng/tag';

import { ApiError, Health } from '../../core/models/api.models';
import { PlatformApiService } from '../../core/services/api/platform-api.service';
import { AuthService } from '../../core/services/auth/auth.service';

@Component({
  selector: 'app-settings',
  imports: [DatePipe, ButtonModule, InputTextModule, TagModule],
  templateUrl: './settings.component.html',
  styles: `
    .head p { margin: 0.25rem 0 0; }
    .soon { display: flex; align-items: center; justify-content: space-between; gap: 1rem; flex-wrap: wrap; }
    .soon p { margin: 0.25rem 0 0; }
    .dh-field { margin-bottom: 1rem; }
  `,
})
export class SettingsComponent {
  private readonly platformApi = inject(PlatformApiService);
  protected readonly user = inject(AuthService).user;
  protected readonly health = signal<Health | null>(null);
  protected readonly healthError = signal<ApiError | null>(null);

  constructor() {
    this.checkHealth();
  }

  protected checkHealth(): void {
    this.healthError.set(null);
    this.platformApi.health().subscribe({
      next: (h) => this.health.set(h),
      error: (err: ApiError) => this.healthError.set(err),
    });
  }
}
