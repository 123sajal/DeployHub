import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { MessageService } from 'primeng/api';
import { of, throwError } from 'rxjs';

import { BillingSummary, Project } from '../../../core/models/api.models';
import { BillingApiService } from '../../../core/services/api/billing-api.service';
import { DeploymentApiService } from '../../../core/services/api/deployment-api.service';
import { ProjectApiService } from '../../../core/services/api/project-api.service';
import { CheckoutDismissed, RazorpayCheckoutService } from '../../../core/services/payments/razorpay-checkout.service';
import { ProjectPaymentComponent } from './project-payment.component';

const billing = (over: Partial<BillingSummary> = {}): BillingSummary => ({
  enabled: true,
  paymentsConfigured: true,
  pricePerDeploy: 4900,
  currency: 'INR',
  credits: 0,
  freeProjectAvailable: false,
  freeProjects: 2,
  freeProjectsLeft: 0,
  ...over,
});
const project = (over: Partial<Project> = {}) => ({ id: 305, name: 'employeemanagement', free: false, ...over }) as Project;

describe('ProjectPaymentComponent (/app/projects/payment?project=305)', () => {
  const create = vi.fn();
  const summary = vi.fn();
  const createOrder = vi.fn();
  const verify = vi.fn();
  const pay = vi.fn();
  const order = { orderId: 'order_1', keyId: 'rzp_test_x', amount: 4900, currency: 'INR', name: 'A', email: 'a@x.in' };

  async function render(p: Project = project(), b: BillingSummary = billing()) {
    [create, summary, createOrder, verify, pay].forEach((f) => f.mockReset());
    summary.mockReturnValue(of(b));
    await TestBed.configureTestingModule({
      imports: [ProjectPaymentComponent],
      providers: [
        provideRouter([]),
        MessageService,
        { provide: ProjectApiService, useValue: { get: () => of(p) } },
        { provide: DeploymentApiService, useValue: { create } },
        { provide: BillingApiService, useValue: { summary, createOrder, verify } },
        { provide: RazorpayCheckoutService, useValue: { pay } },
      ],
    }).compileComponents();
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(ProjectPaymentComponent);
    fixture.componentRef.setInput('project', '305');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return {
      fixture,
      el: fixture.nativeElement as HTMLElement,
      toast: vi.spyOn(TestBed.inject(MessageService), 'add'),
      click: async (label: string) => {
        const button = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find((b) =>
          b.textContent?.includes(label),
        );
        expect(button, `button "${label}"`).toBeTruthy();
        button!.click();
        await new Promise((resolve) => setTimeout(resolve));
      },
    };
  }

  it('shows the project and the price, pays with Checkout, verifies, deploys and opens the deployment', async () => {
    const { el, click } = await render();
    expect(el.textContent).toContain('employeemanagement');
    expect(el.textContent).toContain('₹49');
    expect(el.textContent).toContain('Your 2 free projects are already used');
    createOrder.mockReturnValue(of(order));
    pay.mockResolvedValue({ paymentId: 'pay_1', signature: 'a'.repeat(64) });
    verify.mockReturnValue(of(billing({ credits: 1 })));
    create.mockReturnValue(of({ id: 9 }));

    await click('Pay ₹49 & deploy');

    expect(pay).toHaveBeenCalledWith(order, 'One deploy of employeemanagement');
    expect(verify).toHaveBeenCalledWith('order_1', 'pay_1', 'a'.repeat(64));
    expect(create.mock.calls[0][0]).toBe(305);
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/app/deployments', 9]);
  });

  it('closing Checkout charges nothing and deploys nothing', async () => {
    const { click, toast } = await render();
    createOrder.mockReturnValue(of(order));
    pay.mockRejectedValue(new CheckoutDismissed());

    await click('Pay ₹49 & deploy');

    expect(verify).not.toHaveBeenCalled();
    expect(create).not.toHaveBeenCalled();
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ summary: 'Payment cancelled' }));
  });

  it('uses a paid deploy you already have instead of asking to pay', async () => {
    const { el, click } = await render(project(), billing({ credits: 1 }));
    expect(el.textContent).not.toContain('Pay ₹49');
    create.mockReturnValue(of({ id: 3 }));

    await click('Deploy now');

    expect(createOrder).not.toHaveBeenCalled();
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/app/deployments', 3]);
  });

  it("says when payments aren't set up", async () => {
    const { el } = await render(project(), billing({ paymentsConfigured: false }));
    expect(el.textContent).toContain("Payments aren't set up");
    expect(el.textContent).not.toContain('Pay ₹49');
  });

  it('a free project just deploys', async () => {
    const { el } = await render(project({ free: true }));
    expect(el.textContent).toContain('this is one of your free projects');
    expect(el.textContent).not.toContain('Pay ₹49');
  });

  it('shows the error again if the paid deploy was used up meanwhile', async () => {
    const { click, toast } = await render(project(), billing({ credits: 1 }));
    create.mockReturnValue(throwError(() => ({ status: 402, title: 'Payment required', detail: 'x', fieldErrors: {} })));

    await click('Deploy now');

    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ summary: 'Payment required' }));
    expect(summary).toHaveBeenCalledTimes(2); // reloaded
  });
});
