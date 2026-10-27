import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';

import { PaymentOrder } from '../../models/api.models';

const CHECKOUT_SCRIPT = 'https://checkout.razorpay.com/v1/checkout.js';

/** What Checkout hands back after a successful payment; the server verifies it before crediting anything. */
export interface CheckoutResult {
  paymentId: string;
  signature: string;
}

/** Thrown when the user closes Checkout without paying (not an error worth a red message). */
export class CheckoutDismissed extends Error {
  constructor() {
    super('Payment cancelled');
  }
}

interface RazorpayResponse {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature: string;
}

type RazorpayConstructor = new (options: Record<string, unknown>) => {
  open(): void;
  on(event: 'payment.failed', handler: (response: { error?: { description?: string } }) => void): void;
};

/**
 * Razorpay Checkout: Razorpay's own payment window (UPI, cards, netbanking). Card and UPI details are typed into
 * Razorpay's page, never into DeployHub. Its script is loaded only when someone actually pays.
 */
@Injectable({ providedIn: 'root' })
export class RazorpayCheckoutService {
  private readonly document = inject(DOCUMENT);
  private loading: Promise<RazorpayConstructor> | null = null;

  async pay(order: PaymentOrder, description: string): Promise<CheckoutResult> {
    const Razorpay = await this.load();
    return new Promise<CheckoutResult>((resolve, reject) => {
      const checkout = new Razorpay({
        key: order.keyId,
        order_id: order.orderId,
        amount: order.amount,
        currency: order.currency,
        name: 'DeployHub',
        description,
        prefill: { name: order.name, email: order.email },
        theme: { color: '#6366f1' },
        handler: (r: RazorpayResponse) => resolve({ paymentId: r.razorpay_payment_id, signature: r.razorpay_signature }),
        modal: { ondismiss: () => reject(new CheckoutDismissed()) },
      });
      // Checkout lets the user retry a failed attempt in the same window, so only closing it ends the payment.
      checkout.on('payment.failed', () => {});
      checkout.open();
    });
  }

  private load(): Promise<RazorpayConstructor> {
    const existing = (this.document.defaultView as unknown as { Razorpay?: RazorpayConstructor } | null)?.Razorpay;
    if (existing) return Promise.resolve(existing);
    this.loading ??= new Promise<RazorpayConstructor>((resolve, reject) => {
      const script = this.document.createElement('script');
      script.src = CHECKOUT_SCRIPT;
      script.async = true;
      script.onload = () => {
        const loaded = (this.document.defaultView as unknown as { Razorpay?: RazorpayConstructor }).Razorpay;
        if (loaded) resolve(loaded);
        else reject(new Error('Razorpay Checkout did not load.'));
      };
      script.onerror = () => {
        this.loading = null; // let the next attempt try again
        reject(new Error("Razorpay Checkout couldn't be loaded. Check your internet connection."));
      };
      this.document.head.appendChild(script);
    });
    return this.loading;
  }
}
