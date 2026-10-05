# Roadmap

**Status: feature-complete for version 1, not yet released.** Everything in the
[specification](docs/spec.md) is built and tested (milestones M0–M6). Before a first release, it
needs trying out by real teams, and the integrations below need checking against the real
services.

## What's in version 1

- **Time tracking:** timers, day and week timesheets, approvals that lock submitted weeks,
  budgets with alerts, and rates resolved per project, task or person and kept on each entry.
- **Invoicing:** invoices from uninvoiced time and expenses, gapless numbering, two named taxes or
  VAT categories, PDFs, sending and reminders, a public invoice page, and online payment through
  the account's own Stripe (no application fee).
- **E-invoicing:** Factur-X/ZUGFeRD hybrid PDFs, XRechnung and Peppol BIS, checked in tests
  against the official rules and PDF/A-3; sending over Peppol through Storecove.
- **Accounting:** invoices and payments pushed to QuickBooks Online and Xero through a retrying,
  idempotent queue.
- **Moving from Harvest:** an importer that brings over people, clients, projects, tasks, rates,
  time, expenses and invoices, then compares hours, billable amounts and invoice totals with
  Harvest's own reports; an optional sync window for teams that switch gradually; and a CSV
  fallback that works offline.
- **Reports:** time, detailed time with bulk edit, uninvoiced, budgets and expenses, exported as
  CSV or XLSX.
- **Browser extension** for Chrome and Firefox: a timer in the toolbar and a "Track time" button
  on Jira, Asana, GitHub, Linear and Trello. Timers started anywhere show everywhere within two
  seconds.
- **Your data:** Move out, one step on every plan and in every state: a full export that imports
  into any instance, where you can go next, and what's still connected with how to end each. And
  account deletion after a grace period.
- **Security:** row-level security per account, two-factor sign-in (which accounts can require),
  a password re-check before sensitive actions, breached-password checks, and an audit log.
- **A public REST API** with personal access tokens, documented in [docs/api.md](docs/api.md) and
  in the app.
- **Honest Robin Cloud** additions only: billing through Paddle with a price locked per
  subscription, cancelling in the app in two clicks, and privacy-respecting product analytics
  (decision record 0015). The self-hosted edition has every feature.

## Before the first release

- Check the integrations in their sandboxes: Stripe, Paddle, Storecove, QuickBooks Online and
  Xero. Each is tested against a stand-in; the places where their APIs were assumed are marked
  **VERIFY** in the code.
- Check the extension's button placement on the five live sites. The selectors are data the
  instance serves, so later fixes don't need a store review.
- Run the Harvest import against a real Harvest account. It's tested against a stand-in for
  Harvest's API, and its speed target (250,000 entries in 15 minutes) hasn't been measured.
- Check the CSV import's header names against a real Harvest export; the mapping screen covers
  differences in the meantime.
- Sign in to Harvest with OAuth, which needs a registered Harvest app (today: a personal access
  token).
- Decide whether a timer that ran past midnight should end "the next day".
- Publish the extension in the Chrome Web Store and Firefox Add-ons.

## Later

From the specification's backlog (§2.2): recurring invoices, estimates, credit notes, mobile
apps, idle detection, a calendar view, webhooks, Slack commands, and national e-invoicing
platforms such as KSeF, Chorus Pro and SDI, in the order customers need them.

## How it's tested

GitHub Actions runs the backend suite in both editions, the frontend and extension unit tests,
licence checks, and end-to-end tests against the packaged Docker image, including the extension
loaded in Chromium. The end-to-end job fails if the app logs any error. A load test
(`PERF=1 ./gradlew :backend:test --tests '*LoadTest'`) seeds an account with a million entries and
checks report and timesheet response times.
