import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { providePrimeNG } from 'primeng/config';
import { ConfirmationService, MessageService } from 'primeng/api';

import { routes } from './app.routes';
import { apiErrorInterceptor } from './core/interceptors/api-error.interceptor';
import { csrfRetryInterceptor } from './core/interceptors/csrf-retry.interceptor';
import { sessionExpiredInterceptor } from './core/interceptors/session-expired.interceptor';
import { AuthService } from './core/services/auth/auth.service';
import { DeployHubPreset } from './core/utils/theme';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top', anchorScrolling: 'enabled' }),
    ),
    provideHttpClient(
      // Responses pass through these in reverse order: csrfRetry and sessionExpired see raw errors
      // before apiErrorInterceptor turns them into ApiError objects.
      withInterceptors([apiErrorInterceptor, sessionExpiredInterceptor, csrfRetryInterceptor]),
      // CSRF: Angular copies the backend's XSRF-TOKEN cookie into this header on every POST/PUT/DELETE.
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
    ),
    // Ask the backend who is logged in before the first route is resolved (survives page refresh).
    provideAppInitializer(() => inject(AuthService).restore()),
    providePrimeNG({
      ripple: false,
      theme: { preset: DeployHubPreset, options: { darkModeSelector: '.app-dark' } },
    }),
    MessageService,
    ConfirmationService,
  ],
};
