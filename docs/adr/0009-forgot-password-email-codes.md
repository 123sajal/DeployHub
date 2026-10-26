# ADR 0009: Forgot password with emailed codes

Status: accepted · Date: 2026-10-26

## Context
Users who forget their password had no way back into their account. The user asked for a reset with a code
sent to their email (Gmail).

## Decisions
1. **A 6-digit one-time code by email, entered on the same page** (not a link). It works with any mail app and
   needs no public URL for DeployHub. `POST /api/auth/forgot-password {email}` sends it,
   `POST /api/auth/reset-password {email, code, newPassword}` uses it. The user then logs in normally.
2. **Only a hash is stored.** Table `password_reset_codes` (V9) keeps a BCrypt hash, expiry (10 minutes), guesses
   made and when it was used. A new request deletes the account's older codes; deleting the account deletes them.
3. **Guessing is bounded three ways:** 5 guesses per code (counted with an atomic `UPDATE ... WHERE attempts < 5`,
   so parallel guesses can't exceed it), 30 wrong codes per IP per hour, and the code expires. Marking a code
   used is also atomic, so it can reset the password only once.
4. **No account discovery.** `forgot-password` always answers 202. Unknown and disabled accounts get the same
   BCrypt work and no email; the email is sent on a background thread, so response time doesn't tell either.
   Wrong, expired and unknown cases share one message.
5. **No email flooding.** At most 5 codes per address and 20 per IP per hour (429 after), counted in
   `audit_events` like the login limits.
6. **Gmail SMTP via Spring Boot Mail** (`smtp.gmail.com:587`, STARTTLS, 10 s timeouts) with a Google App
   password in the git-ignored `.env` (`MAIL_USERNAME`, `MAIL_PASSWORD`). Without them nothing is sent and the
   code is logged, so the feature works in development with no account.
7. **A reset ends the login lockout:** failed logins before the reset no longer count.
8. Audit events: `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_FAILED`, `PASSWORD_RESET_THROTTLED`, `PASSWORD_RESET`.

## Consequences / limitations
- Sessions already logged in elsewhere stay valid after a reset (until logout or 30 minutes idle). Ending them
  needs a per-request check against the account, a later change.
- Gmail's free sending limit is about 500 emails a day; a real product would use a mail service (e.g. SES, Brevo).
- Anyone with access to the mailbox can reset the password: the email account is the root of trust.
