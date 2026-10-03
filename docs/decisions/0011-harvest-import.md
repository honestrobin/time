# 0011. Harvest import (M2)

- Status: accepted
- Date: 2026-10-03
- Spec reference: §6, AT-2.1 to AT-2.6

## Context

The importer is the front door (§6): it must bring a Harvest account over completely, survive crashes and rate limits, never duplicate on a re-run, and prove that the totals match. The Harvest API shapes in `HarvestModels.kt` were checked against the API docs on 30 September and 1 October; the rest of this record is about how we use them.

## Decisions

1. **Personal access token first.** An admin pastes a token and the account ID (§6.2 option B). OAuth (option A) needs a registered Honest Robin app with Harvest; `HarvestSettings` has the client ID and secret, but the flow is not built. The token is checked against `/company` before the job is queued, stored encrypted, and deleted when the run completes or is cancelled. A failed run keeps it so it can resume.
2. **One run, many small transactions.** Each page of Harvest data is written in one transaction together with the checkpoint (`import_jobs.progress`: step, next page URL, done steps, counts). A run that dies resumes at the page after the last committed one. Production runs go through a db-scheduler one-time task, which restarts runs whose process died; tests run inline (`honestrobin.harvest.run-inline`).
3. **Idempotency through `external_links`.** Every imported row has a link (`system = harvest`). A re-run updates linked rows in place. Rows that are not linked yet but match an existing record by name (clients, tasks, projects within a client, teams, expense categories) or email (people) are adopted rather than duplicated, so importing into a non-empty account works.
4. **People arrive as `pending_invite`.** Nobody gets an email until an admin invites them. People who already sign in to the account keep the role and permissions they have here. Harvest access roles map to admin, manager or member; `billable_rates_manager`, `project_creator` and `managed_projects_invoice_manager` map to the three permission flags.
5. **Money.** Project rates, budgets, fees, entry rates and invoices use the client's currency. Person rates, task default rates and expense category prices use the account currency, because Harvest does not attach a currency to them. Entry rate snapshots are copied from Harvest, never re-resolved, so historical totals match.
6. **Time entries.** Durations are Harvest's `hours` × 3600, capped at 24 hours with a warning. Running timers arrive stopped with the time they had, with a warning. Approved and invoiced entries arrive locked. Weeks with submitted or approved entries get a `timesheet_submissions` row.
7. **Invoices** arrive read-only (`source = harvest_import`). States map draft → draft, open → sent (or partially_paid if something was paid), paid → paid, closed → void. Line items and payments are replaced on each re-run. Afterwards the default invoice sequence continues after the highest imported number, keeping its prefix and zero padding. Time entries and expenses point at their imported invoice; per-line links are not available from the API.
8. **Estimates** are kept as read-only archived documents (`archived_documents`, kind `harvest_estimate`) until estimates are a feature.
9. **Verification** compares, per project and month, hours and billable amount with Harvest's time report, and invoice count and total per client. Harvest rounds the summed amount while we round each entry, so a difference of up to half a minor unit per entry is shown as "matches (rounding)", not as a difference.
10. **Failures are records, not crashes.** A record that refers to something that was not imported is skipped and listed as an issue; the run continues. Errors from Harvest itself (5xx after retries, network) stop the run with a message and a resume button. A 429 is waited out and does not count as a failed attempt.

11. **Sync window (phase 4).** Optional, chosen at the start, at most 30 days: the job keeps its token and every 15 minutes asks each list with `updated_since` (the import's start the first time, then the last successful sync), through the same upserts. Harvest's version wins while both run, except entries invoiced or timing here, which are kept and listed. Deletions in Harvest aren't seen (the API doesn't report them). "Stop syncing" or the last day forgets the token.
12. **CSV import (§6.6).** Harvest's detailed time report and its clients, contacts and projects exports. The kind and the columns are recognised from the headers (a synonym table), and a mapping screen covers anything else, so the import doesn't depend on exact header text; dates are ISO, day-first or month-first (detected, or asked when every value fits both), hours decimal with either mark or h:mm, currency codes alone or as "Euro - EUR". A preview shows every problem row, what will be created and the totals before anything is written. Each time row is keyed by a hash of what it says plus its occurrence in the file, so identical rows are both kept and importing the same file again changes nothing; keys live under the `harvest-csv` system, apart from API ids. People are matched by email, then name; new ones are added without an invitation, with the email given in the preview or a placeholder on the reserved `.invalid` domain. AT-2.4 (same totals as the API import for the same data) is tested by writing the fixture out as a detailed time report. VERIFY the header names against a real export.

## Not done yet

- **OAuth sign-in to Harvest** (decision 1): needs a registered Harvest app.
- The invoices report export isn't read by the CSV import; invoices come over with the API import.
- The performance target of 250,000 entries in 15 minutes (§6.7) is not measured. With 2,000 entries per page that is 125 requests for the entries, well inside the rate limit; the database side is untested at that size.
