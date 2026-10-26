import { LowerCasePipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';

import { Health } from '../../models/api.models';
import { PlatformApiService } from '../../services/api/platform-api.service';
import { AuthService } from '../../services/auth/auth.service';
import { MessageService } from 'primeng/api';

interface NavItem {
  label: string;
  icon: string;
  link: string;
}

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, LowerCasePipe],
  templateUrl: './app-shell.component.html',
  styleUrl: './app-shell.component.scss',
})
export class AppShellComponent {
  private readonly platformApi = inject(PlatformApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly messages = inject(MessageService);

  protected readonly nav: NavItem[] = [
    { label: 'Dashboard', icon: 'pi-th-large', link: '/app/dashboard' },
    { label: 'Projects', icon: 'pi-folder', link: '/app/projects' },
    { label: 'Deployments', icon: 'pi-cloud-upload', link: '/app/deployments' },
    { label: 'Settings', icon: 'pi-cog', link: '/app/settings' },
  ];
  protected readonly sidebarOpen = signal(false);
  protected readonly health = signal<Health | null>(null);
  protected readonly apiDown = signal(false);
  protected readonly loggingOut = signal(false);
  protected readonly user = this.auth.user;
  protected readonly initials = computed(() =>
    (this.user()?.displayName ?? '?')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]!.toUpperCase())
      .join(''),
  );

  constructor() {
    const destroyRef = inject(DestroyRef);
    // Close the mobile drawer after navigating.
    this.router.events
      .pipe(
        filter((e) => e instanceof NavigationEnd),
        takeUntilDestroyed(destroyRef),
      )
      .subscribe(() => this.sidebarOpen.set(false));

    this.platformApi
      .health()
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe({
        next: (h) => this.health.set(h),
        error: () => this.apiDown.set(true),
      });
  }

  protected logout(): void {
    this.loggingOut.set(true);
    this.auth.logout().subscribe(() => {
      this.loggingOut.set(false);
      this.messages.add({ severity: 'info', summary: 'You have been logged out' });
      this.router.navigate(['/login']);
    });
  }

  protected toggleSidebar(): void {
    this.sidebarOpen.update((open) => !open);
  }
}
