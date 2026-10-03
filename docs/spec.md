# Honest Robin: Time — product specification

What version 1 of Honest Robin: Time does and how it is built: an open-source (AGPL-3.0) time tracking and invoicing app for freelancers and agencies, with the workflows Harvest users know. Written on 30 September 2026, before the build. Where the build differs, a decision record in [`decisions/`](decisions/) says why; section numbers (§) are referred to throughout the code.

## 0. Conventions

- Milestones (§17) are built in order; each ends with its acceptance tests passing.
- **VERIFY** marks facts about third-party systems (Harvest API, Storecove, Stripe, Paddle, QuickBooks, Xero) that must be checked against their current documentation.
- **DEFAULT** marks a chosen default: the behaviour stays, the implementation may change.
- The non-negotiables (§1.2) hold for every code path, configuration flag and edition.
- Harvest's logo, colours, icons, copy and UI assets are never copied. Only workflows and the mental model are matched.

## 1. Product summary

### 1.1 One-liner
The time tracking and invoicing workflows agencies know from Harvest, a seat price locked for the life of each subscription, a move from Harvest in minutes, and e-invoicing built in.

### 1.2 Non-negotiables (trust charter, enforced in code)
1. **No usage-based pricing mechanics.** No code may meter projects, clients, tasks, invoices or entries for billing. Plan limits exist only on seats (cloud) and never on self-hosted.
2. **Full export always works**, on every plan and edition, including free and suspended/unpaid accounts (read-only + export for at least 12 months after lapse).
3. **Self-hosted edition is complete.** Same codebase, same features. The only cloud-only capabilities are those that depend on a paid third-party network service operated by Honest Robin (e-invoice sending via Honest Robin's access-point contract, managed Harvest continuous sync infrastructure). Self-hosters can plug in their own provider credentials for these.
4. **No dark patterns.** No hidden fees, no forced annual plans, cancel in-app in two clicks.
5. **Licence:** AGPL-3.0 for everything in the repo. **No CLA.** Contributions use DCO sign-off (`Signed-off-by:`).
6. **EU data residency** for Honest Robin Cloud.

### 1.3 Target users
- Primary: agencies and professional-services firms, 2–25 seats, currently on Harvest.
- Secondary: solo freelancers (free tier).
- Personas: *Admin* (billing, invoices, settings), *Manager* (projects, budgets, approvals, reports), *Member* (tracks time, submits timesheets).

## 2. Scope

### 2.1 v1 (launch)
| Area | Included |
|---|---|
| Time | Start/stop timer (one running per user), day and week timesheet views, duration or start/end entry, notes, billable flag, edit/delete, copy last week's rows, timesheet reminders (email) |
| Structure | Clients (+ contacts), projects, tasks (global task list + per-project task assignment), people, roles/teams, per-project member assignment |
| Rates | Billable and cost rates at person, project, task and project-task level; Harvest-equivalent bill-by modes |
| Budgets | Hours and money budgets per project/task/person; alerts at configurable % thresholds |
| Approvals | Weekly submit → approve/reject by manager; approved entries lock |
| Expenses | Expense categories, entries with receipt upload, billable flag, attach to project |
| Invoicing | Create from uninvoiced time/expenses or free-form; taxes (2 tax rates like Harvest + EU VAT modes), discounts, currency per client, numbering sequences, PDF, email send, payment reminders, record payments, online payment via user-connected Stripe |
| EU e-invoicing | EN 16931 data completeness, ZUGFeRD/Factur-X hybrid PDF (PDF/A-3 + CII XML), UBL export, Peppol sending via provider adapter (Storecove first) |
| Reports | Time report, detailed time report, uninvoiced report, budget report, expense report; filters; CSV and XLSX export |
| Capture | Browser extension (Chrome + Firefox, MV3) with timer popup and injected buttons for Jira, Asana, GitHub, Linear, Trello |
| Accounting | QuickBooks Online and Xero: push invoices and payments |
| Data | Harvest importer (API + CSV fallback), full account export (JSON + CSV zip), public REST API v1 with personal access tokens |
| Admin | Email/password + magic link, Google and Microsoft OAuth sign-in, roles, audit log, account settings, data deletion |
| Cloud billing | Paddle subscriptions (cloud edition only) |

### 2.2 Out of scope for v1 (backlog, P1/P2)
Recurring invoices, estimates, credit notes, retainers with rollover, native mobile apps, desktop app, idle detection, calendar view, Slack commands, SAML/SCIM, multi-level approvals, scheduling/forecasting, direct national e-invoice platforms (KSeF, Chorus Pro, SDI, Croatian fiscalization), Harvest-compatible API facade, PayPal payments, webhooks (P1), mileage.

## 3. Architecture

### 3.1 Stack (DEFAULT — chosen for self-host simplicity, the maintainer's Kotlin experience, and the mature Java e-invoicing ecosystem)
| Layer | Choice |
|---|---|
| Backend | Kotlin, Spring Boot 3, JDK 21 |
| DB access | jOOQ (typed SQL) + Flyway migrations |
| Database | PostgreSQL 16 (only required dependency) |
| Background jobs | db-scheduler (Postgres-backed; no Redis) |
| Frontend | React + TypeScript + Vite, TanStack Query/Router, a headless component lib (Radix) + own design system |
| PDF | HTML templates → openhtmltopdf (PDF/A-3 capable) |
| E-invoice XML | Mustangproject (ZUGFeRD/Factur-X/XRechnung, CII + UBL) |
| File storage | S3-compatible interface; local filesystem driver for self-host |
| Email | SMTP interface (any provider); templates in repo |
| Extension | TypeScript, Manifest V3, shared API client generated from OpenAPI |
| API docs | OpenAPI 3.1 generated from code; published docs site |
| Analytics | PostHog (cloud edition only, server-side + client, **disabled by default in self-host**) |
| Packaging | Single Docker image (backend serves built SPA) + `docker-compose.yml` with Postgres |

Monolith, modular by package. No microservices.

### 3.2 Modules
`auth`, `accounts` (tenancy, members, roles), `catalog` (clients, contacts, projects, tasks, assignments, rates), `time`, `expenses`, `approvals`, `budgets`, `invoicing`, `einvoice` (formats + provider adapters), `payments` (Stripe Connect), `accounting` (QBO, Xero), `reports`, `importers.harvest`, `export`, `api` (public REST), `billing` (Paddle; cloud only), `notifications`, `audit`, `files`.

### 3.3 Editions
One build. `HONESTROBIN_EDITION=cloud|selfhost`. The edition switch may only toggle: Paddle billing module, PostHog, Honest Robin-operated provider credentials, marketing links. It must never toggle product features (see §1.2.3). CI runs the full test suite in both editions.

### 3.4 Multi-tenancy
Row-level tenancy: every tenant table has `account_id`, enforced by a repository layer and Postgres Row-Level Security as defence in depth. A user may belong to several accounts.

### 3.5 Conventions
- Money: `bigint` minor units + ISO 4217 `currency` column. Never floats.
- Durations: `integer` seconds. Display rounding is a per-account setting (none, 6, 15, 30 min), applied on display and invoicing, never on storage.
- Dates: `spent_date` is a `date` in the user's account timezone; timestamps are `timestamptz` UTC.
- IDs: UUIDv7 internally; public API exposes them as strings.
- All mutations write an `audit_log` row (actor, entity, before/after diff).
- i18n from day one: all UI strings in message files; English at launch; locale-aware number, date and currency formatting.

## 4. Data model (core tables)

Columns listed are the essential ones; add `id`, `account_id`, `created_at`, `updated_at`, `archived_at` where sensible.

| Table | Key columns | Notes |
|---|---|---|
| `accounts` | name, timezone, week_start, default_currency, time_rounding, fiscal settings (legal name, address, VAT ID, company reg no.), invoice defaults | Tenant |
| `users` | email, name, password_hash (nullable), locale | Global |
| `memberships` | user_id, role (`admin`/`manager`/`member`), is_billable_default, default_billable_rate, cost_rate, weekly_capacity_seconds, is_active | Per account |
| `teams` / `team_memberships` | name | Harvest "roles" import here |
| `clients` | name, currency, address, vat_id, peppol_id (scheme + value), company_reg_no, is_active | |
| `client_contacts` | client_id, name, email, phone, is_invoice_recipient | |
| `tasks` | name, is_default, default_billable, default_rate | Global task list |
| `projects` | client_id, name, code, is_billable, bill_by (`project`/`tasks`/`people`/`none`), hourly_rate, budget_by, budget_hours, budget_amount, budget_alert_percent, notify_when_over_budget, show_budget_to_all, starts_on, ends_on, notes, is_fixed_fee, fee_amount, is_active | Mirror Harvest semantics (VERIFY against API docs) |
| `project_tasks` | project_id, task_id, billable, hourly_rate, budget_hours, is_active | |
| `project_members` | project_id, membership_id, is_manager, hourly_rate, budget_hours | |
| `time_entries` | membership_id, project_id, task_id, spent_date, duration_seconds, started_at, ended_at, timer_started_at (non-null = running), notes, billable, billable_rate_snapshot, cost_rate_snapshot, external_reference (source, id, url, title), approval_state (`draft`/`submitted`/`approved`/`rejected`), invoice_id (nullable), is_locked | At most one running entry per membership (partial unique index) |
| `expense_categories` | name, unit_name, unit_price | |
| `expenses` | membership_id, project_id, category_id, spent_date, amount_minor, currency, units, notes, billable, receipt_file_id, invoice_id, approval_state | |
| `timesheet_submissions` | membership_id, week_start_date, state, submitted_at, decided_by, decided_at, comment | |
| `invoice_sequences` | name, prefix, next_number, padding | Supports per-year sequences |
| `invoices` | client_id, number, sequence_id, issue_date, due_date, currency, state (`draft`/`open`/`sent`/`partially_paid`/`paid`/`void`), subject, notes, purchase_order, buyer_reference, tax1 (name, percent), tax2, vat_mode (`standard`/`reverse_charge`/`exempt`/`outside_scope`) + exemption_reason, discount_percent, totals (subtotal, tax, total, paid, due), period_start, period_end, payment_terms, einvoice_status, source (`native`/`harvest_import`), is_read_only | Imported invoices are read-only |
| `invoice_lines` | invoice_id, kind (`service`/`product`/`expense`), project_id, description, quantity, unit_price_minor, tax1_applies, tax2_applies, vat_category_code, line_total_minor | |
| `invoice_time_links` / `invoice_expense_links` | invoice_line_id, time_entry_id / expense_id | For "mark as invoiced" |
| `payments` | invoice_id, amount_minor, paid_at, method (`manual`/`stripe`/`other`), external_id, notes | |
| `einvoice_transmissions` | invoice_id, provider, format, document_blob_id, status (`queued`/`sent`/`delivered`/`failed`/`rejected`), provider_ref, last_error, attempts | |
| `integrations` | kind (`stripe`/`qbo`/`xero`/`storecove`), encrypted credentials, status, settings | Secrets encrypted with envelope encryption |
| `external_links` | entity_type, entity_id, system (`harvest`/`qbo`/`xero`), external_id | Idempotency for imports and syncs |
| `import_jobs` | source, mode (`api`/`csv`), status, phase, progress JSON, started_by, token_ref (encrypted), sync_until, stats JSON, verification JSON, error | |
| `api_tokens` | membership_id, name, token_hash, last_used_at, expires_at, scopes | |
| `audit_log` | actor, action, entity_type, entity_id, diff JSON, ip, at | Append-only |
| `files` | storage_key, mime, size, sha256 | |

## 5. Domain rules

### 5.1 Rate resolution (billable rate snapshot on each entry)
Resolve at entry save; re-resolve when rates change **only** for unlocked, uninvoiced entries, and only after the user confirms a prompt ("Update rates on N uninvoiced entries?").
- `bill_by = project` → project.hourly_rate
- `bill_by = tasks` → project_task.hourly_rate ?? task.default_rate
- `bill_by = people` → project_member.hourly_rate ?? membership.default_billable_rate
- `bill_by = none` or entry not billable → 0
Cost rate: membership.cost_rate at time of entry.
(VERIFY Harvest precedence and replicate it exactly so imported totals match.)

### 5.2 Timers
- Starting a timer stops any running timer for that user (duration finalised).
- Running entries show live elapsed time; server is source of truth (`timer_started_at`).
- Timers crossing midnight stay on the `spent_date` they started on (Harvest behaviour; VERIFY).
- Max single entry: 24h; warn over 12h.

### 5.3 Approvals and locking
- Submitted weeks are read-only to the member; managers can approve/reject.
- Approved or invoiced entries are locked; admins can unlock with an audit log reason.
- Account setting: approvals on/off (default off).

### 5.4 Budgets
- Budget usage computed from entries (hours or billable amount, per budget_by).
- Alert emails to project managers at threshold % and at 100%, once per crossing.

### 5.5 Invoicing
- Create from: (a) uninvoiced billable time + expenses for a client/project over a date range, grouped by project, task, person, or detailed entries (Harvest-equivalent grouping options); (b) free-form lines.
- Numbers assigned on first send or on "mark as sent" (drafts have no number), unique per sequence, never reused. Void keeps the number.
- Editing a sent invoice creates an audit record; editing a paid invoice is blocked.
- Deleting an invoice un-links its time and expenses (they become uninvoiced again).
- Tax: up to two named taxes (Harvest compatibility) **or** EU VAT mode with category codes per line. Reverse charge requires buyer VAT ID and prints the legal note.
- Rounding: line totals rounded half-up to minor units; tax computed per EN 16931 rules (VERIFY).
- Email send: PDF attached (hybrid ZUGFeRD when enabled), link to hosted invoice page with "Pay now" (if Stripe connected), BCC sender option.
- Reminders: configurable schedule (e.g. 3 days before due, on due date, 7 days after); stop on payment.

### 5.6 Online payments
- Stripe Connect (Standard accounts, OAuth). Payment goes directly to the user's Stripe; Honest Robin takes **no** application fee.
- Hosted invoice page creates a Checkout Session on the connected account; webhook records the payment and updates invoice state.

## 6. Harvest importer

The importer is the front door. It must be fast, transparent, verifiable and safe.

### 6.1 Entry points
1. Onboarding screen: "Coming from Harvest? Import everything." (primary path)
2. Settings → Import.
3. Marketing site CTA deep-links into onboarding with `?import=harvest`.

### 6.2 Auth (VERIFY all details against current Harvest API v2 docs)
- Option A: Harvest OAuth2 (register a Honest Robin OAuth app). Preferred.
- Option B: personal access token + account ID pasted by the user (for self-hosted instances without an OAuth app).
- Required headers include `Authorization: Bearer`, `Harvest-Account-Id`, and a descriptive `User-Agent` with a contact email.
- Store token encrypted, scoped to the import job. Delete when the job ends (or sync window closes). Show "Revoke this token in Harvest when done" with a link.

### 6.3 Phases (run as one background job with checkpoints; resumable)
| Phase | Entities | Goal |
|---|---|---|
| 1. Structure | company settings, users, roles, clients, contacts, tasks, projects, task assignments, user assignments, expense categories | Usable workspace in < 60 s for a typical agency |
| 2. Recent work | time entries and expenses for the last 90 days; open (unpaid) invoices | Current week and uninvoiced work correct |
| 3. History | all older time entries, expenses, all invoices + line items + payments, estimates stored as read-only archive JSON | Complete record |
| 4. Sync window (optional) | incremental sync via `updated_since` every 15 min for up to 30 days | Parallel running; user picks cutover date |

### 6.4 Rules
- **Rate limits:** respect Harvest limits (documented as 100 requests / 15 s for general endpoints — VERIFY). Token-bucket client; honour `Retry-After` / 429; exponential backoff with jitter.
- **Pagination:** max page size; cursor/`next_page` links.
- **Idempotency:** every imported row has an `external_links` record (`system=harvest`). Re-running updates in place; never duplicates.
- **Users:** imported users become memberships with `pending_invite` status; admin chooses whom to invite. Inactive Harvest users import as inactive (their entries still attach).
- **Running timers** in Harvest import as stopped entries with current elapsed duration, flagged in the import report.
- **Locked/approved/invoiced** states are preserved.
- **Invoices:** imported with original numbers, state and payments; marked `source=harvest_import`, `is_read_only=true`; PDFs regenerated from line items with a small "Imported from Harvest" footer. Invoice sequences continue after the highest imported number.
- **Rates:** imported as configured, and each time entry keeps Harvest's billable rate snapshot so historical totals match exactly.
- **Archived** clients/projects import archived.
- **External references** on time entries (e.g. Jira/Asana links) are preserved.
- **Failure handling:** a failing record is logged and skipped, not fatal; the import report lists skipped records with reasons and a retry button.

### 6.5 Verification screen (must ship with v1)
After phases 1–3, show a side-by-side table: for each client, project and month — hours, billable amount, invoiced amount, uninvoiced amount — **Honest Robin value vs. Harvest value** (Harvest values fetched from Harvest's reports endpoints, VERIFY). Rows match → green check; mismatch → amber with drill-down to the differing entries. Summary headline: "12,483 entries, 214 invoices, 38 projects imported. All totals match."

### 6.6 CSV fallback
Accept Harvest's own CSV exports (detailed time report, invoices report, clients, contacts, projects). Auto-detect column headers; mapping UI for unrecognised columns; same idempotency via a synthetic key (hash of date + person + project + task + notes + hours). Must work fully offline for self-hosters.

### 6.7 Import UX
- Progress: phase list with counts and ETA; user can leave and gets an email when done.
- Preview before commit for CSV imports.
- Performance target: 250,000 time entries imported in under 15 minutes wall-clock (bounded by Harvest rate limits).

## 7. EU e-invoicing

### 7.1 Data completeness (always on)
Every invoice must be able to carry the EN 16931 core fields: seller and buyer legal name, address, VAT ID, company registration number, electronic address (Peppol ID), buyer reference / PO, payment terms and means (IBAN/BIC), line VAT category codes, exemption reasons, currency. Validation runs before "send as e-invoice" with human-readable errors ("Client VAT ID missing — required for reverse charge").

### 7.2 Formats
- **Hybrid PDF:** ZUGFeRD 2.x / Factur-X (EN 16931 profile) — PDF/A-3 with embedded CII XML. Toggle per account; default on for EU accounts.
- **XRechnung** (CII) and **Peppol BIS Billing 3.0** (UBL) XML download.
- Validate generated XML with the official validators/schematrons in CI (KoSIT validator for XRechnung; Peppol BIS schematron). VERIFY current versions.

### 7.3 Sending via provider
- Interface `EInvoiceProvider { registerLegalEntity(), lookupRecipient(peppolId|vatId), send(document), status(ref), receiveWebhook() }`.
- First adapter: **Storecove** (VERIFY API, sandbox, pricing). Keep adapters swappable; a second adapter is P1.
- Flow: recipient lookup on client save (show "Reachable via Peppol ✓") → send → track status in `einvoice_transmissions` → surface delivery/rejection on the invoice.
- Cloud: Honest Robin's provider contract; per-document cost passed through transparently on the bill. Self-host: admin enters own provider credentials.

## 8. Accounting sync (QuickBooks Online, Xero)
- OAuth connection per account. Map Honest Robin clients ↔ accounting customers/contacts (auto-match by name + email; manual override).
- Push on invoice send: invoice with lines, tax codes mapped via a settings table. Push payments recorded in Honest Robin.
- One-way (Honest Robin → accounting) in v1. Idempotent via `external_links`. Retry queue with visible error state.
- VERIFY rate limits, tax-code models and multi-currency behaviour for both APIs.

## 9. Browser extension
- Chrome + Firefox, Manifest V3, TypeScript. Auth via OAuth device-style flow or PAT paste.
- Popup: running timer, start/stop, project/task picker with search, notes, recent entries.
- Content scripts inject a "Track time" button into: Jira (Cloud issue view), Asana (task pane), GitHub (issues + PRs), Linear (issue view), Trello (card back). Button pre-fills notes with item title and stores `external_reference` {source, id, url, title}.
- Remember last project/task per external workspace.
- Selector configs in a versioned JSON file so breakages can be hot-fixed without store review where possible (MV3 permitting; VERIFY).

## 10. Public REST API v1
- Base `/api/v1`, JSON, OpenAPI 3.1, cursor pagination, `If-Match` optimistic concurrency via `updated_at` ETags.
- Auth: personal access tokens (hashed at rest, scoped read/write) and OAuth2 for third-party apps (P1).
- Resources: me, users, clients, contacts, projects, tasks, assignments, time_entries (incl. start/stop timer), expenses, invoices (read, create, send), payments, reports.
- Rate limit: generous and published (e.g. 600 req/min per token); `429` + `Retry-After`.
- Harvest-compatible facade (same paths/shapes) is P2 — keep internal models close enough to make it feasible.

## 11. Auth, roles and permissions
| Capability | Admin | Manager | Member |
|---|---|---|---|
| Track own time/expenses | ✓ | ✓ | ✓ |
| View/edit others' time | ✓ | managed projects | — |
| Approve timesheets | ✓ | managed projects | — |
| Manage clients/projects/tasks | ✓ | managed projects (configurable) | — |
| See rates & amounts | ✓ | configurable | configurable (default hidden) |
| Invoices & payments | ✓ | configurable | — |
| Reports | all | managed projects | own |
| Integrations, billing, import, export, settings | ✓ | — | — |

Sign-in: email + password (argon2id), magic link, Google, Microsoft. Optional TOTP 2FA. Session cookies (HttpOnly, SameSite=Lax) for the web app; tokens for API/extension.

## 12. Reports
- Time: by client, project, task, person; date presets (this week, last month, custom); billable vs non-billable; filters by client/project/task/person/team/billable/approval state.
- Detailed time: entry-level list with all fields; bulk edit (project/task/billable) for unlocked entries.
- Uninvoiced: billable time and expenses not on an invoice, by client/project, with "Create invoice" action.
- Budget: per project — budget, spent, remaining, % used; over-budget highlighted.
- Expenses: by category, project, person.
- All reports: CSV and XLSX export; shareable saved filters (P1).
- Performance: report queries < 1 s p95 for 1M entries per account (use indexes + materialised aggregates if needed).

## 13. Export and account lifecycle
- One-click full export: zip with JSON (lossless, documented schema) + CSVs + all invoice PDFs/XML + receipt files. Async job, email link, available on every plan and edition, including lapsed accounts (§1.2.2).
- Account deletion: admin-initiated, 14-day grace, then hard delete incl. backups rotation policy documented.
- Self-host ↔ cloud migration: the export zip is importable into either edition (round-trip test in CI).

## 14. Cloud billing (edition=cloud only)
- Paddle (merchant of record). Plans: Free Solo (1 seat) and Team (per seat, monthly or annual). Prices in configuration, not code.
- **Price lock:** each subscription stores its locked unit price; the pricing system must never raise an existing subscription's unit price. Enforce with a DB constraint/check and a test.
- Seat count = active members with sign-in access. Deactivated members are free and keep their history.
- Switcher offer: coupon granting free months equal to remaining Harvest term (max 12), applied manually by admin tooling in v1.
- Change-of-control and price-lock clauses live in the ToS (legal, not code), but the product surfaces them on the billing page.

## 15. Non-functional requirements
- **Security:** OWASP ASVS L2 as target; secrets via envelope encryption (KMS in cloud, key file in self-host); CSRF protection; strict CSP; dependency scanning; SAST in CI; encrypted backups with monthly restore test (cloud).
- **Privacy/GDPR:** EU hosting; DPA template; subprocessor list page; data export/deletion (§13); PostHog in EU region, no session recording of form inputs; self-host sends zero telemetry by default.
- **Performance:** timesheet week view < 300 ms p95 server time; timer start/stop < 150 ms p95; SPA initial load < 2.5 s on 4G.
- **Reliability (cloud):** 99.9% monthly target; status page; nightly backups + PITR.
- **Accessibility:** WCAG 2.2 AA; full keyboard operation of timesheet and timer.
- **Keyboard shortcuts:** start/stop timer, new entry, navigate days/weeks (document them; aim for Harvest-familiar ergonomics without copying).
- **Observability:** structured logs, OpenTelemetry traces, Prometheus metrics endpoint (self-host friendly).

## 16. Repository, licensing and CI
```
honestrobin-time/
  backend/            Kotlin Spring Boot app
  frontend/           React SPA
  extension/          Browser extension
  packages/api-client Generated TS client (OpenAPI)
  deploy/             Dockerfile, docker-compose.yml, Helm chart (P1)
  docs/               Self-host guide, API docs, ADRs, data export schema
  e2e/                Playwright tests
```
- `LICENSE`: AGPL-3.0. `CONTRIBUTING.md`: DCO sign-off, **no CLA**. `SECURITY.md`. `CODE_OF_CONDUCT.md`.
- CI: build, unit + integration tests (Testcontainers Postgres), e2e (Playwright), both editions, e-invoice XML validation, export/import round-trip, license header check, dependency licence check (reject AGPL-incompatible deps).
- Release: semver; Docker image published to GHCR; changelog generated from conventional commits.

## 17. Milestones and acceptance tests

Each milestone is done when its tests pass in CI in both editions.

### M0 — Skeleton
- Repo, CI, Docker image, compose file, auth (email/password + magic link), account creation, empty SPA shell, i18n scaffolding, audit log.
- **AT-0.1** `docker compose up` on a clean machine yields a working instance; first user becomes admin.
- **AT-0.2** Edition flag toggles only the allowed modules (automated test enumerates feature endpoints and asserts parity).

### M1 — Core tracking
Clients, contacts, tasks, projects, assignments, rates, timer, timesheets (day/week), expenses, approvals, budgets + alerts, roles/permissions.
- **AT-1.1** Starting a second timer stops the first; both entries have correct durations.
- **AT-1.2** Rate resolution matches §5.1 for all four bill_by modes (table-driven test).
- **AT-1.3** Submitted week is read-only to the member; manager approval locks entries; admin unlock writes an audit entry.
- **AT-1.4** Budget alert email fires once when crossing 80% and once at 100%.
- **AT-1.5** Member without rate visibility never receives rate/amount fields from any API endpoint.

### M2 — Harvest importer
- **AT-2.1** Against a recorded Harvest API fixture account (build a mock server from VERIFIED API shapes), import completes all phases; verification screen shows all totals matching.
- **AT-2.2** Re-running the import produces zero duplicates and updates changed records.
- **AT-2.3** 429 responses are handled with backoff; the job resumes after a simulated crash from its last checkpoint.
- **AT-2.4** CSV import of the same fixture data produces identical totals to the API import.
- **AT-2.5** Imported invoices are read-only; invoice numbering continues after the highest imported number.
- **AT-2.6** Structure phase for a 25-person, 200-project fixture completes in < 60 s (mocked latency 150 ms).

### M3 — Invoicing and payments
- **AT-3.1** Invoice created from uninvoiced time marks exactly those entries invoiced; deleting the invoice reverts them.
- **AT-3.2** Numbering: no gaps on concurrent sends; voided numbers never reused.
- **AT-3.3** Two-tax and EU VAT modes compute correct totals (fixtures incl. reverse charge and 0% exemptions).
- **AT-3.4** Stripe test-mode payment via hosted page marks invoice paid via webhook; Honest Robin application fee = 0.
- **AT-3.5** Reminder schedule sends correct emails and stops after payment.

### M4 — Browser extension
- **AT-4.1** Timer started from extension appears in the web app within 2 s, and vice versa.
- **AT-4.2** Injected button works on fixture pages for all five integrations and stores `external_reference`.

### M5 — E-invoicing and accounting
- **AT-5.1** Generated Factur-X/ZUGFeRD PDFs pass PDF/A-3 validation and EN 16931 schematron; XRechnung passes KoSIT validator; UBL passes Peppol BIS schematron.
- **AT-5.2** Storecove sandbox: recipient lookup, send, delivery status update end-to-end.
- **AT-5.3** QBO and Xero sandbox: invoice and payment push is idempotent across retries.

### M6 — Launch hardening
Export/delete, public API + docs, Paddle billing with price lock, reports + exports, security review, performance tests, self-host docs, marketing-site hooks (calculator deep link), PostHog funnel events.
- **AT-6.1** Full export → import into a fresh instance (other edition) reproduces all data (checksum on JSON export).
- **AT-6.2** Attempting to raise an existing subscription's unit price fails (constraint + test).
- **AT-6.3** Lapsed account remains read-only with working export.
- **AT-6.4** Load test: 1M entries per account; reports p95 < 1 s; timesheet p95 < 300 ms.
- **AT-6.5** PostHog funnel events fire in cloud edition only: `signup`, `import_started`, `import_verified`, `first_timer_started`, `first_invoice_sent`, `invoice_paid_online`, `subscription_started`.
