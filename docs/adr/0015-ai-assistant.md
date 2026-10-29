# ADR 0015: AI assistant (sign-up, help, explaining failures) and the student discount

Status: accepted · Date: 2026-10-28

## Context
The user wanted an assistant like Razorpay's "RAY": sign-up as a conversation that checks each answer and suggests
fixes, help while deploying, and a helper that appears by itself when something goes wrong. Decisions agreed in chat
(also in docs/ai-assistant.pdf): Java inside deployhub-backend (not a separate Python service), Claude API with two
models, collect name / email / student or working professional, **no Aadhaar** (legal limits on private companies,
leak risk; DeployHub has no need for it), students may verify with a student ID photo or a college email and get
Rs 5 off each paid deploy, AI spending capped at Rs 20 per day while there are no users.

## Decisions
1. **Package `assistant`** in the backend: `AssistantModel` (interface; `ClaudeAssistantModel` uses the official
   Anthropic Java SDK, created lazily so DeployHub starts without a key), `AssistantBudget`, `AssistantPrompts`,
   `SecretMasker`, `OfflineReplies`, `EmailTypos`, `EmailProofs`. Services: `AssistantService`, `StudentService`,
   `EmailCodeService`. Controllers: `/api/assistant/**`, `/api/students/verify/**`; `PATCH /api/auth/me`.
2. **Models:** `claude-haiku-5-5` for chat, short explanations and the ID card check (low effort, fast, cheap);
   `claude-opus-5-5` for failed deployments (medium effort, reads the end of the log, server-side refusal fallback on).
   Both are settings.
3. **The AI only writes text.** It has no tools: it can't click, change settings or deploy. Logs, errors, page names
   and repository text are wrapped in tags and the instructions say they are data, never instructions (prompt
   injection). Facts it quotes (prices, free projects) come from the live settings.
4. **Secrets never reach the AI.** `SecretMasker` replaces the project's own env var values and repository token
   wherever they appear, applies the log rules (Bearer, URL credentials, GitHub/AWS tokens), then extra shapes
   (`sk-ant-`, `rzp_`, JWTs, private keys, `NAME=value` for names with pass/pwd/secret/token/key/auth). Passwords are
   typed in normal hidden fields and never sent to any assistant endpoint.
5. **Money:** every AI call is counted in `assistant_usage` (per day in India time, per `user:<id>` or `ip:<address>`,
   cost in thousandths of a paisa from the model's list price and `usd-to-inr`). Before a call: key set? today's total
   under `daily-budget-inr` (20)? subject under its daily request limit (50 per user, 20 per network before login)?
   Otherwise the **built-in replies** answer, with a notice saying why. The check is before the call and the cost known
   after, so the last call of a day can exceed the cap by one reply. A failed deployment's explanation is saved
   (`deployment_explanations`), so viewing it again is free; a double click can't pay twice.
6. **Always works without the AI:** sign-up checks (name, email format, typo suggestions such as gmial.com) are plain
   code; every AI answer has a built-in fallback (no key, budget/limit reached, outage, refusal, empty answer).
7. **Email proof at sign-up:** `POST /signup/email-code` (409 if the email has an account) and `/verify` return a
   signed, 30-minute proof (HMAC, random key per process); `register` accepts optional `occupation` and `emailProof`
   and sets `users.email_verified_at`. The simple form (`/signup/form`) still works without it. Codes reuse the
   password-reset design and limits (hash only, 10 min, 5 guesses, per-email/IP sending limits).
8. **Student check:** an ID photo (consent required, JPG/PNG checked by content, max 5 MB, decoded at reduced size,
   shrunk to 1568 px and re-encoded as JPEG, which drops EXIF/GPS) is sent once to Haiku with structured output
   (readable, student card, name, institution, valid until, problem). Java decides: one shared name part of 3+ letters,
   not expired, date capped at 4 years, no date = 1 year. The photo is never stored; only `student_verified_until`
   and `student_verified_by`. 5 photos per day. Or a code to a college email (`.ac.in`, `.edu`, `.edu.in`), valid 1 year.
9. **Discount:** `BillingProperties.priceFor(verifiedStudent)` = price minus `student-discount` (500 paise), never below
   Rs 1; used for the Razorpay order amount (decided by the backend) and the 402 message; `GET /api/billing` returns
   `yourPrice`, `verifiedStudent`, `studentDiscount`.
10. **No chat storage:** the browser keeps the conversation (max 12 turns, 2000 characters each) and sends it with each
    question; the server merges turns so the API sees user/assistant alternating. Replies are not streamed (a typing
    indicator instead); Haiku answers in a few seconds.
11. **Frontend:** `/signup` is the conversation (details card with green ticks, "Did you mean...?" buttons, code step,
    student/professional buttons, password fields, optional ID upload); a message ending in "?" goes to the chat.
    Floating "Ask the assistant" on every app page and the login page. `AssistantHintComponent` pops up on a failed
    deployment (explains on click: Opus), missing required settings (on click), a failed payment and a refused import
    (explains right away: Haiku). Settings > Student discount.
12. **`/api/auth/me` during a database outage** answers from the session (profile fields empty) instead of 503, as before.

## Consequences
- Without `ANTHROPIC_API_KEY` everything works with built-in answers; the student ID check then points to the college
  email. The key must come from console.anthropic.com (a Claude subscription can't be used for an app's users).
- Rs 20 a day is roughly 130 Haiku chats or about 2 Opus explanations; raise `daily-budget-inr` when users arrive.
- A photo can be edited, so the ID check is a light check; the discount is small for that reason.
- V15 migration (additive): users.occupation / email_verified_at / student_verified_until / student_verified_by,
  email_codes, assistant_usage, deployment_explanations (every foreign key indexed).

## Amendment (same day): Google Gemini as the default provider
The user asked for a free option and chose Gemini. `deployhub.assistant.provider` selects `gemini` (default) or
`claude`; settings moved under `deployhub.assistant.gemini.*` / `.claude.*` (`AssistantProperties.chatModel()` etc.
follow the provider).
- `GeminiAssistantModel`: REST `POST /v1beta/models/{model}:generateContent` with `java.net.http` (no new library),
  key in the `x-goog-api-key` header (never in the URL), roles `user`/`model`, `systemInstruction`, thinking parts
  dropped from the answer, thinking tokens counted as output, output limit at least 8192 (thinking may count). Safety
  finish reasons and `promptFeedback.blockReason` are refusals; `MAX_TOKENS` is a cut-off; any HTTP error (e.g. 429
  when Google's free quota is used up) falls back to the built-in answers. The ID card is sent as `inline_data` JPEG
  with `responseMimeType: application/json`, parsed leniently. Model names are checked before going into the URL.
- Models (Google's docs, October 2026): `gemini-3.5-flash-lite` for chat and short explanations, `gemini-3.8-flash` for
  failed deployments; both have a free tier. Paid prices are in `AssistantBudget` (Flash at the higher 2027 price).
- **Free tier** (`gemini.free-tier=true`, default): calls are recorded with cost 0 (the per-user limits still apply).
  Google says free-tier content is used to improve its products, so **student ID photos are never sent** on the free
  tier (`mayReadIdCards()`; `/api/assistant/status` returns `idCardCheck: false`; the UI shows only the college email
  way). Chats and masked logs do go to Google on the free tier; the chat says not to paste passwords or keys.
- Claude stays available unchanged (`provider=claude`).
- Testing switch `deployhub.assistant.gemini.id-cards-on-free-tier` (default false) allows student ID photos on the free tier; the user turned it on in their local `.env` only, to test with their own or a sample card.
