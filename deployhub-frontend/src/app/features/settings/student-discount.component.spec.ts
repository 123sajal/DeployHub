import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { CurrentUser } from '../../core/models/api.models';
import { AssistantApiService } from '../../core/services/api/assistant-api.service';
import { BillingApiService } from '../../core/services/api/billing-api.service';
import { AuthService } from '../../core/services/auth/auth.service';
import { StudentDiscountComponent } from './student-discount.component';

describe('StudentDiscountComponent', () => {
  const api = { verifyStudentIdCard: vi.fn(), sendCollegeEmailCode: vi.fn(), verifyCollegeEmail: vi.fn(), status: vi.fn() };
  let idCardCheck = true;
  const user = signal<CurrentUser | null>(null);
  const auth = { user, refresh: vi.fn(() => of(null)), updateOccupation: vi.fn() };
  const billing = {
    summary: vi.fn(() =>
      of({ enabled: true, paymentsConfigured: true, pricePerDeploy: 4900, currency: 'INR', credits: 0,
        freeProjectAvailable: false, freeProjects: 2, freeProjectsLeft: 0, yourPrice: 4900, verifiedStudent: false,
        studentDiscount: 500 }),
    ),
  };

  async function render(current: CurrentUser) {
    Object.values(api).forEach((f) => f.mockReset());
    api.status.mockReturnValue(of({ aiAvailable: true, idCardCheck }));
    user.set(current);
    await TestBed.configureTestingModule({
      imports: [StudentDiscountComponent],
      providers: [
        { provide: AssistantApiService, useValue: api },
        { provide: AuthService, useValue: auth },
        { provide: BillingApiService, useValue: billing },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(StudentDiscountComponent);
    await fixture.whenStable();
    return fixture;
  }

  const alice: CurrentUser = { id: 1, email: 'a@x.com', displayName: 'Alice', occupation: 'STUDENT' };

  it('shows the discount and keeps the upload locked until the user agrees', async () => {
    const fixture = await render(alice);
    expect(fixture.nativeElement.textContent).toContain('Students get ₹5 off every paid deploy (₹44 instead of ₹49).');
    const file = fixture.nativeElement.querySelector('input[type=file]') as HTMLInputElement;
    expect(file.disabled).toBe(true);

    const consent = fixture.nativeElement.querySelector('input[type=checkbox]') as HTMLInputElement;
    consent.click();
    await fixture.whenStable();
    expect(file.disabled).toBe(false);
  });

  it('points to the college email when the photo check is off', async () => {
    idCardCheck = false;
    const fixture = await render(alice);
    idCardCheck = true;
    expect(fixture.nativeElement.querySelector('input[type=file]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('The photo check is off on this DeployHub');
    expect(fixture.nativeElement.querySelector('#college-email')).toBeTruthy();
  });

  it('shows a verified student and their date', async () => {
    const fixture = await render({ ...alice, studentVerifiedUntil: '2099-01-31', studentVerifiedBy: 'ID_CARD' });
    expect(fixture.nativeElement.textContent).toContain('Verified student until 2099-01-31 (student ID card).');
    expect(fixture.nativeElement.querySelector('input[type=file]')).toBeNull();
  });

  it('verifies with a college email code', async () => {
    const fixture = await render(alice);
    api.sendCollegeEmailCode.mockReturnValue(of(undefined));
    api.verifyCollegeEmail.mockReturnValue(
      of({ verified: true, validUntil: '2027-10-28', method: 'COLLEGE_EMAIL', message: 'Your college email is confirmed.' }),
    );
    const email = fixture.nativeElement.querySelector('#college-email') as HTMLInputElement;
    email.value = 'alice@iitd.ac.in';
    email.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    [...fixture.nativeElement.querySelectorAll('button')].find((b) => (b as HTMLElement).textContent?.includes('Send code'))!.click();
    await fixture.whenStable();
    expect(api.sendCollegeEmailCode).toHaveBeenCalledWith('alice@iitd.ac.in');

    const code = fixture.nativeElement.querySelector('#college-code') as HTMLInputElement;
    code.value = '123456';
    code.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    [...fixture.nativeElement.querySelectorAll('button')].find((b) => (b as HTMLElement).textContent?.includes('Confirm'))!.click();
    await fixture.whenStable();
    expect(api.verifyCollegeEmail).toHaveBeenCalledWith('alice@iitd.ac.in', '123456');
    expect(fixture.nativeElement.textContent).toContain('Your college email is confirmed.');
    expect(auth.refresh).toHaveBeenCalled();
  });

  it('shows why a code was refused', async () => {
    const fixture = await render(alice);
    api.sendCollegeEmailCode.mockReturnValue(
      throwError(() => ({ status: 400, title: 'Validation failed', detail: '', fieldErrors: { email: 'Use your college email address (ending in .ac.in).' } })),
    );
    const email = fixture.nativeElement.querySelector('#college-email') as HTMLInputElement;
    email.value = 'alice@gmail.com';
    email.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    [...fixture.nativeElement.querySelectorAll('button')].find((b) => (b as HTMLElement).textContent?.includes('Send code'))!.click();
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Use your college email address');
  });
});
