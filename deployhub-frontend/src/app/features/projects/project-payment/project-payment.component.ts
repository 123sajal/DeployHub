import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { firstValueFrom, forkJoin } from 'rxjs';

import { AssistantHintComponent } from '../../../core/components/assistant/assistant-hint.component';
import { PageStateComponent } from '../../../core/components/shared/page-state.component';
import { ApiError, BillingSummary, Project } from '../../../core/models/api.models';
import { BillingApiService } from '../../../core/services/api/billing-api.service';
import { DeploymentApiService } from '../../../core/services/api/deployment-api.service';
import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { CheckoutDismissed, RazorpayCheckoutService } from '../../../core/services/payments/razorpay-checkout.service';
import { newIdempotencyKey } from '../../../core/utils/idempotency-key';
import { formatPrice } from '../../../core/utils/money';

/**
 * /app/projects/payment?project=<id>: paying for one deploy of a project that isn't free.
 * Pay -> Razorpay Checkout -> the server verifies the payment -> the deploy starts and its page opens.
 */
@Component({
  selector: 'app-project-payment',
  imports: [RouterLink, ButtonModule, MessageModule, PageStateComponent, AssistantHintComponent],
  templateUrl: './project-payment.component.html',
  styleUrl: './project-payment.component.scss',
})
export class ProjectPaymentComponent implements OnInit {
  /** The ?project= query parameter, bound by withComponentInputBinding(). */
  readonly project = input<string>();

  private readonly projectApi = inject(ProjectApiService);
  private readonly billingApi = inject(BillingApiService);
  private readonly deploymentApi = inject(DeploymentApiService);
  private readonly checkout = inject(RazorpayCheckoutService);
  private readonly messages = inject(MessageService);
  private readonly router = inject(Router);

  protected readonly details = signal<Project | null>(null);
  protected readonly billing = signal<BillingSummary | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly busy = signal(false);
  protected readonly price = computed(() => {
    const b = this.billing();
    return b ? formatPrice(b.yourPrice ?? b.pricePerDeploy, b.currency) : '';
  });
  /** The last payment error, for the assistant to explain. */
  protected readonly paymentError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    const id = Number(this.project());
    if (!Number.isInteger(id) || id <= 0) {
      this.error.set({ status: 404, title: 'No project chosen', detail: 'Open a project and press Deploy.', fieldErrors: {} });
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    forkJoin({ project: this.projectApi.get(id), billing: this.billingApi.summary() }).subscribe({
      next: ({ project, billing }) => {
        this.details.set(project);
        this.billing.set(billing);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.error.set(err);
        this.loading.set(false);
      },
    });
  }

  /** Order -> Razorpay Checkout -> server verifies the payment -> deploy with the new credit. */
  protected async payAndDeploy(): Promise<void> {
    const p = this.details();
    if (!p || this.busy()) return;
    this.busy.set(true);
    this.paymentError.set(null);
    try {
      const order = await firstValueFrom(this.billingApi.createOrder());
      const paid = await this.checkout.pay(order, `One deploy of ${p.name}`);
      this.billing.set(await firstValueFrom(this.billingApi.verify(order.orderId, paid.paymentId, paid.signature)));
      this.messages.add({ severity: 'success', summary: 'Payment received', detail: 'Starting your deploy.' });
      await this.deploy();
    } catch (e) {
      this.busy.set(false);
      if (e instanceof CheckoutDismissed) {
        this.messages.add({ severity: 'info', summary: 'Payment cancelled', detail: 'Nothing was charged.' });
        return;
      }
      const err = e as Partial<ApiError> & { message?: string };
      this.messages.add({ severity: 'error', summary: err.title ?? 'Payment failed', detail: err.detail ?? err.message });
      this.paymentError.set(`${err.title ?? 'Payment failed'}: ${err.detail ?? err.message ?? 'unknown error'}`);
    }
  }

  /** Deploys with a credit (or for free); opens the new deployment's page. */
  protected async deploy(): Promise<void> {
    const p = this.details();
    if (!p) return;
    this.busy.set(true);
    try {
      const d = await firstValueFrom(this.deploymentApi.create(p.id, newIdempotencyKey()));
      this.messages.add({ severity: 'info', summary: 'Deployment queued', detail: `#${d.id}` });
      await this.router.navigate(['/app/deployments', d.id]);
    } catch (e) {
      this.busy.set(false);
      const err = e as ApiError;
      this.messages.add({ severity: 'error', summary: err.title, detail: err.detail });
      if (err.status === 402) this.load(); // the credit was used meanwhile: show the current state
    }
  }
}
