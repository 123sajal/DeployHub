import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';

import { ApiError, BillingSummary, Occupation, StudentVerification } from '../../core/models/api.models';
import { AssistantApiService } from '../../core/services/api/assistant-api.service';
import { BillingApiService } from '../../core/services/api/billing-api.service';
import { AuthService } from '../../core/services/auth/auth.service';
import { formatPrice } from '../../core/utils/money';

/** Settings > Student discount: student or working professional, and the student check (ID photo or college email). */
@Component({
  selector: 'app-student-discount',
  imports: [FormsModule, ButtonModule, InputTextModule, MessageModule],
  template: `
    <section class="dh-card" aria-labelledby="student-title">
      <h2 class="dh-card-title" id="student-title">Student discount</h2>
      @if (user(); as u) {
        <div class="dh-field">
          <label for="occupation">I am a</label>
          <select id="occupation" [ngModel]="u.occupation ?? ''" (ngModelChange)="saveOccupation($event)"
                  [disabled]="savingOccupation()">
            <option value="" disabled>Choose...</option>
            <option value="STUDENT">Student</option>
            <option value="PROFESSIONAL">Working professional</option>
          </select>
        </div>

        @if (billing() && !billing()!.enabled) {
          <p-message severity="info"
                     text="Deploys are free on DeployHub right now, so the student discount isn't needed. You can still verify now; it will apply when paid deploys start." />
        }
        @if (verifiedUntil(); as until) {
          <p-message severity="success"
                     [text]="'Verified student until ' + until + (u.studentVerifiedBy === 'ID_CARD' ? ' (student ID card).' : ' (college email).')
                       + (yourPriceText() ? ' ' + yourPriceText() : '')" />
        } @else {
          <p class="dh-muted">
            {{ discountText() || 'Students get a discount on every paid deploy.' }} Verify one of two ways; you can
            check again once it expires.
          </p>
          @if (expired()) {
            <p-message severity="warn" text="Your student check has expired. Verify again to keep the discount." />
          }

          <h3>1. Student ID card photo</h3>
          @if (!idCardCheck()) {
            <p class="dh-muted">
              The photo check is off on this DeployHub (the AI isn't set up, or it runs on a free tier where the
              provider may use what it receives). Use your college email below.
            </p>
          } @else {
          <label class="consent">
            <input type="checkbox" [(ngModel)]="consent" name="consent" />
            DeployHub's AI may check my card once. The photo is deleted right after the check; only the
            "valid until" date is kept.
          </label>
          <label class="upload" [class.disabled]="!consent || busy()">
            <i class="pi pi-id-card" aria-hidden="true"></i> Upload photo (JPG or PNG, up to 5 MB)
            <input type="file" accept="image/jpeg,image/png" (change)="upload($event)" [disabled]="!consent || busy()" />
          </label>
          }

          <h3>2. College email</h3>
          @if (!codeSent()) {
            <div class="row">
              <label for="college-email" class="sr-only">College email</label>
              <input pInputText id="college-email" type="email" [(ngModel)]="collegeEmail" name="collegeEmail"
                     placeholder="name@college.ac.in" />
              <p-button label="Send code" size="small" (onClick)="sendCode()" [disabled]="busy() || !collegeEmail.trim()" />
            </div>
          } @else {
            <div class="row">
              <label for="college-code" class="sr-only">Code from the email</label>
              <input pInputText id="college-code" inputmode="numeric" maxlength="6" [(ngModel)]="code" name="code"
                     placeholder="6-digit code" />
              <p-button label="Confirm" size="small" (onClick)="confirmCode()" [disabled]="busy() || code.trim().length !== 6" />
              <p-button label="Change email" size="small" [text]="true" (onClick)="codeSent.set(false)" />
            </div>
            <small class="dh-muted">Code sent to {{ collegeEmail }}.</small>
          }
        }
        @if (error(); as message) {
          <p-message severity="error" [text]="message" role="alert" />
        }
        @if (result(); as r) {
          <p-message severity="success" [text]="r.message" />
        }
      }
    </section>
  `,
  styles: `
    h3 { font-size: 0.9rem; margin: 1.1rem 0 0.5rem; }
    select { width: 100%; padding: 0.55rem 0.7rem; background: var(--dh-bg-elevated); color: var(--dh-text);
      border: 1px solid var(--dh-border-strong); border-radius: var(--dh-radius); font: inherit; }
    .dh-field { margin-bottom: 1rem; }
    .consent { display: flex; gap: 0.5rem; align-items: flex-start; color: var(--dh-text-muted); font-size: 0.85rem;
      margin-bottom: 0.6rem; }
    .upload { display: inline-flex; align-items: center; gap: 0.5rem; padding: 0.5rem 0.9rem; border-radius: var(--dh-radius);
      background: var(--dh-primary-strong); color: var(--dh-on-primary); font-weight: 600; cursor: pointer; }
    .upload input { display: none; }
    .upload.disabled { opacity: 0.45; pointer-events: none; }
    .row { display: flex; gap: 0.5rem; flex-wrap: wrap; align-items: center; }
    .row input { flex: 1; min-width: 180px; }
    p-message { display: block; margin-top: 0.75rem; }
  `,
})
export class StudentDiscountComponent {
  private readonly auth = inject(AuthService);
  private readonly api = inject(AssistantApiService);
  private readonly billingApi = inject(BillingApiService);

  protected readonly user = this.auth.user;
  protected readonly billing = signal<BillingSummary | null>(null);
  protected readonly busy = signal(false);
  protected readonly savingOccupation = signal(false);
  protected readonly codeSent = signal(false);
  protected readonly idCardCheck = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly result = signal<StudentVerification | null>(null);
  protected consent = false;
  protected collegeEmail = '';
  protected code = '';

  /** Today in India time as yyyy-MM-dd, like the server's dates (which compare correctly as text). */
  private readonly today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());
  protected readonly verifiedUntil = computed(() => {
    const until = this.user()?.studentVerifiedUntil;
    return until && until >= this.today ? until : null;
  });
  protected readonly expired = computed(() => {
    const until = this.user()?.studentVerifiedUntil;
    return !!until && until < this.today;
  });
  /** Before verifying: what students get. */
  protected readonly discountText = computed(() => {
    const b = this.billing();
    return b?.enabled && b.studentDiscount
      ? `Students get ${formatPrice(b.studentDiscount, b.currency)} off every paid deploy `
        + `(${formatPrice(b.pricePerDeploy - b.studentDiscount, b.currency)} instead of ${formatPrice(b.pricePerDeploy, b.currency)}).`
      : '';
  });
  /** After verifying: what this user pays. */
  protected readonly yourPriceText = computed(() => {
    const b = this.billing();
    return b?.enabled && b.verifiedStudent && b.yourPrice !== undefined
      ? `You pay ${formatPrice(b.yourPrice, b.currency)} instead of ${formatPrice(b.pricePerDeploy, b.currency)} per paid deploy.`
      : '';
  });

  constructor() {
    this.loadBilling();
    this.api.status().subscribe({ next: (s) => this.idCardCheck.set(s.idCardCheck), error: () => this.idCardCheck.set(false) });
  }

  private loadBilling(): void {
    this.billingApi.summary().subscribe({ next: (b) => this.billing.set(b), error: () => this.billing.set(null) });
  }

  protected saveOccupation(occupation: Occupation): void {
    this.savingOccupation.set(true);
    this.auth.updateOccupation(occupation).subscribe({
      next: () => this.savingOccupation.set(false),
      error: (err: ApiError) => {
        this.savingOccupation.set(false);
        this.error.set(err.detail || 'Could not save.');
      },
    });
  }

  protected upload(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.run(this.api.verifyStudentIdCard(file));
  }

  protected sendCode(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.sendCollegeEmailCode(this.collegeEmail.trim()).subscribe({
      next: () => {
        this.busy.set(false);
        this.codeSent.set(true);
      },
      error: (err: ApiError) => this.failed(err),
    });
  }

  protected confirmCode(): void {
    this.run(this.api.verifyCollegeEmail(this.collegeEmail.trim(), this.code.trim()));
  }

  private run(request: ReturnType<AssistantApiService['verifyCollegeEmail']>): void {
    this.busy.set(true);
    this.error.set(null);
    this.result.set(null);
    request.subscribe({
      next: (result) => {
        this.busy.set(false);
        this.result.set(result);
        this.auth.refresh().subscribe();
        this.loadBilling();
      },
      error: (err: ApiError) => this.failed(err),
    });
  }

  private failed(err: ApiError): void {
    this.busy.set(false);
    this.error.set(Object.values(err.fieldErrors ?? {})[0] ?? err.detail ?? 'Something went wrong.');
  }
}
