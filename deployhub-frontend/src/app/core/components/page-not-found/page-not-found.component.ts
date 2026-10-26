import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';

@Component({
  selector: 'app-page-not-found',
  imports: [RouterLink, ButtonModule],
  template: `
    <main class="nf">
      <p class="code mono">404</p>
      <h1>This page doesn't exist</h1>
      <p class="dh-muted">The link may be broken, or the page may have moved.</p>
      <p-button label="Back to dashboard" icon="pi pi-arrow-left" routerLink="/app/dashboard" />
    </main>
  `,
  styles: `
    .nf { min-height: 100vh; display: grid; place-content: center; text-align: center; gap: 0.75rem; padding: 1rem; }
    .code { font-size: 3rem; color: var(--dh-primary); margin: 0; }
  `,
})
export class PageNotFoundComponent {}
