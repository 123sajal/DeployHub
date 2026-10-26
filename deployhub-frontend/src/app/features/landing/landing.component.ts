import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';

@Component({
  selector: 'app-landing',
  imports: [RouterLink, ButtonModule],
  templateUrl: './landing.component.html',
  styleUrl: './landing.component.scss',
})
export class LandingComponent {
  protected readonly year = new Date().getFullYear();

  protected readonly features = [
    {
      icon: 'pi-sitemap',
      title: 'Explicit deployment state machine',
      text: 'Every deployment moves through QUEUED → BUILDING → STARTING → LIVE. Illegal transitions are rejected, and failures and cancellations are terminal.',
    },
    {
      icon: 'pi-lock',
      title: 'Concurrency-safe updates',
      text: 'Optimistic locking stops a stale worker from overwriting a cancellation that happened a moment earlier.',
    },
    {
      icon: 'pi-align-left',
      title: 'Persistent, cursor-paged logs',
      text: 'Log events are stored outside the browser session. Clients resume from the last event id they saw, and secrets are masked before storage.',
    },
    {
      icon: 'pi-shield',
      title: 'Untrusted input handled safely',
      text: 'Repository URLs are checked against an allowlist, which blocks SSRF to internal hosts and cloud metadata endpoints.',
    },
    {
      icon: 'pi-database',
      title: 'Oracle-first persistence',
      text: 'Flyway migrations written for Oracle Autonomous Database, with an explicit H2 profile for local development.',
    },
    {
      icon: 'pi-chart-line',
      title: 'Built to be measured',
      text: 'Health endpoints today; queue depth, throughput and latency metrics as the worker architecture lands.',
    },
  ];

  protected readonly roadmap = [
    { label: 'Projects & simulated deployments', state: 'done' },
    { label: 'Oracle persistence', state: 'done' },
    { label: 'Accounts, login & per-user data', state: 'done' },
    { label: 'Durable queue & workers', state: 'next' },
    { label: 'Live SSE logs & rollbacks', state: 'planned' },
    { label: 'Isolated Docker builds', state: 'planned' },
  ];
}
