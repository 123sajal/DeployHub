import { Routes } from '@angular/router';

import { authGuard, guestGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./features/landing/landing.component').then((m) => m.LandingComponent),
    title: 'DeployHub - Deploy your code. Ship with confidence.',
  },
  {
    path: 'login',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/login/login.component').then((m) => m.LoginComponent),
    title: 'Log in - DeployHub',
  },
  {
    path: 'signup',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/signup/signup.component').then((m) => m.SignupComponent),
    title: 'Sign up - DeployHub',
  },
  {
    path: 'forgot-password',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('./features/auth/forgot-password/forgot-password.component').then((m) => m.ForgotPasswordComponent),
    title: 'Reset password - DeployHub',
  },
  {
    path: 'app',
    canActivate: [authGuard],
    canActivateChild: [authGuard],
    loadComponent: () => import('./core/components/layout/app-shell.component').then((m) => m.AppShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      {
        path: 'dashboard',
        loadComponent: () => import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
        title: 'Dashboard - DeployHub',
      },
      {
        path: 'projects',
        loadComponent: () =>
          import('./features/projects/project-list/project-list.component').then((m) => m.ProjectListComponent),
        title: 'Projects - DeployHub',
      },
      {
        path: 'projects/new',
        loadComponent: () =>
          import('./features/projects/project-form/project-form.component').then((m) => m.ProjectFormComponent),
        title: 'New project - DeployHub',
      },
      {
        // Before projects/:id, or "payment" would be read as a project id.
        path: 'projects/payment',
        loadComponent: () =>
          import('./features/projects/project-payment/project-payment.component').then((m) => m.ProjectPaymentComponent),
        title: 'Pay for a deploy - DeployHub',
      },
      {
        path: 'projects/:id',
        loadComponent: () =>
          import('./features/projects/project-detail/project-detail.component').then((m) => m.ProjectDetailComponent),
        title: 'Project - DeployHub',
      },
      {
        path: 'projects/:id/edit',
        loadComponent: () =>
          import('./features/projects/project-form/project-form.component').then((m) => m.ProjectFormComponent),
        title: 'Edit project - DeployHub',
      },
      {
        path: 'deployments',
        loadComponent: () =>
          import('./features/deployments/deployment-list/deployment-list.component').then(
            (m) => m.DeploymentListComponent,
          ),
        title: 'Deployments - DeployHub',
      },
      {
        path: 'deployments/:id',
        loadComponent: () =>
          import('./features/deployments/deployment-detail/deployment-detail.component').then(
            (m) => m.DeploymentDetailComponent,
          ),
        title: 'Deployment - DeployHub',
      },
      {
        path: 'settings',
        loadComponent: () => import('./features/settings/settings.component').then((m) => m.SettingsComponent),
        title: 'Settings - DeployHub',
      },
    ],
  },
  {
    path: '**',
    loadComponent: () =>
      import('./core/components/page-not-found/page-not-found.component').then((m) => m.PageNotFoundComponent),
    title: 'Not found - DeployHub',
  },
];
