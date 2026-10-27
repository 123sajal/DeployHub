# ADR 0010: Paid deploys with Razorpay

Status: accepted · Date: 2026-10-27 · amended the same day: 2 free projects (decision 1)

## Context
The user wants DeployHub to charge for deploys: **the first project is free (later: the first two), every deploy of another
project is paid, through Razorpay** (test mode first). The user asked whether DeployHub should also collect and verify PAN
like Razorpay's own onboarding. It shouldn't: KYC is required of the merchant (the user, done with Razorpay), not
of the people paying, and storing PAN would make DeployHub hold sensitive personal data under the DPDP Act for no
benefit. Constraint as before: nothing outside this Mac is changed, so no webhooks (they need a public URL).

## Decisions
1. **Free projects = the first `deployhub.billing.free-projects` projects a user deploys (2)**, so an app with a
   backend and a frontend costs nothing. Each is claimed inside its first deploy's transaction
   (`users.free_projects_used` counts them (V12), `projects.free_since` marks them). Redeploys of a free project
   are free. Deleting one doesn't give it back, so delete-and-recreate can't get unlimited free projects.
   Projects that existed before V10 stay free. V12 counts only claims made by deploys, so users who had projects
   before billing get the full number. (First version: exactly one free project, `free_project_claimed_at`.)
2. **Credits, not per-project payments.** One paid Razorpay order = one deploy credit (`payments` row, `PAID`,
   `used_at` null). A deploy of a non-free project takes the oldest unused credit in the same transaction that
   queues the deployment; none → **402 Payment Required** with `price` and `currency`. Credits belong to the user,
   so deleting a project never loses one.
3. **Concurrency:** billing decisions take a `SELECT ... FOR UPDATE` lock on the user's row, so two parallel deploys
   can't both claim the last free project or the same credit. CREATED → PAID is a conditional update (`WHERE status =
   'CREATED'`) and `razorpay_payment_id` is unique, so verifying twice or in parallel credits once.
4. **Fair use of credits:** rollbacks are free; a deploy that ends FAILED or CANCELLED gets its credit back
   (`DeploymentTransitionService`, same transaction as the status change). A deleted deployment keeps its credit
   used (`used_at` stays set; `deployment_id` becomes null).
5. **Razorpay Orders API + Checkout, no SDK.** The backend creates the order (amount fixed server-side) with
   Basic auth over Java's HttpClient (`RazorpayGateway`); the browser gets only the public key id and order id and
   opens Checkout, so card/UPI details never touch DeployHub. Checkout's script loads only when someone pays.
6. **Two checks before crediting:** the HMAC-SHA256 signature of `order_id|payment_id` with the key secret
   (constant-time compare), then Razorpay's own record: same order, same amount and currency, and `captured`
   (an `authorized` payment is captured first). Razorpay calls run outside database transactions.
7. **Missed confirmations:** if the browser closes after paying, `GET /api/billing` re-checks the user's unpaid
   orders from the last 24 hours with Razorpay and credits completed ones. This replaces webhooks for now.
8. **Configuration:** `deployhub.billing.*` (enabled, price in paise, currency) and `RAZORPAY_KEY_ID` /
   `RAZORPAY_KEY_SECRET` in the git-ignored `.env`. Without keys the free projects still work and paid deploys
   explain that payments aren't set up (402 / 503). Tests run with billing off; `BillingIntegrationTest` (H2 and
   Oracle) uses an in-memory fake Razorpay.
9. Audit events: `FREE_PROJECT_CLAIMED`, `PAYMENT_ORDER_CREATED`, `PAYMENT_RECEIVED`, `DEPLOY_CREDIT_USED`.
   At most 20 orders per user per hour.

## Consequences / limitations
- No refunds to the card/UPI: a failed deploy returns the *credit*, not money. Refunds would go through Razorpay's
  dashboard or a later refund API.
- No invoices or GST handling yet; needed before charging real customers in India.
- Without webhooks, a payment made after the order's 24-hour re-check window would need a manual credit.
- Paying needs internet access from the backend (api.razorpay.com) and the browser (checkout.razorpay.com).
