import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../../environments/environment';
import { BillingSummary, PaymentOrder } from '../../models/api.models';

@Injectable({ providedIn: 'root' })
export class BillingApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/billing`;

  summary(): Observable<BillingSummary> {
    return this.http.get<BillingSummary>(this.baseUrl);
  }

  /** A Razorpay order for one deploy. 503 when payments aren't set up on the server. */
  createOrder(): Observable<PaymentOrder> {
    return this.http.post<PaymentOrder>(`${this.baseUrl}/orders`, null);
  }

  /** Hands Checkout's result to the server, which checks it with Razorpay before adding the credit. */
  verify(orderId: string, paymentId: string, signature: string): Observable<BillingSummary> {
    return this.http.post<BillingSummary>(`${this.baseUrl}/orders/${orderId}/verify`, { paymentId, signature });
  }
}
