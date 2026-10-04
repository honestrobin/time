# Honest Robin: Time

Open-source time tracking and invoicing for freelancers and agencies. It works the way people who
come from Harvest expect, moves your Harvest account over in minutes, and has e-invoicing built in.
Run it on your own server, or use Honest Robin Cloud (coming later).

> **Not released yet.** Version 1 is feature-complete and being tried out before a first release.
> See the [roadmap](ROADMAP.md) for what's built and what's still to check.

![The week timesheet](docs/screenshots/timesheet.png)

## Built by an AI, in the open

Honest Robin: Time is an experiment in what an AI can build when it's given a clear brief and good
habits. The code, tests and documentation were written by **Claude** (Anthropic's model, working in
[Claude Code](https://claude.com/claude-code)). A human maintainer wrote the
[product specification](docs/spec.md), made the product decisions and kept it on course. Version 1
took four days.

Nothing about that is hidden:

- **Every commit** is co-authored by Claude. The first one gathers the four days it took to build
  version 1; the work since is in the history, change by change.
- **Every decision** someone might question is written down in [`docs/decisions/`](docs/decisions/),
  with the reasoning and what was left open.
- **Every claim is tested.** About 40,000 lines of Kotlin, TypeScript and SQL, covered by around 170
  backend tests in two editions, frontend and extension tests, and end-to-end tests that run the
  packaged app and the browser extension in a real browser. Import totals are checked against
  Harvest's own reports, and e-invoices against the official validation rules.

It hasn't had an independent security audit yet, so treat it as pre-release software. Reports of
anything that looks wrong are very welcome; see [SECURITY.md](SECURITY.md).

## What it does

- **Track time** with timers or day and week timesheets; approvals lock submitted weeks; budgets warn you before they run out.
- **Invoice** from tracked time and expenses, with gapless numbering, taxes or VAT, PDFs, reminders, and online payment through your own Stripe account (no fee from us).
- **E-invoicing:** Factur-X/ZUGFeRD, XRechnung and Peppol BIS, sent over Peppol if you like; invoices and payments can go to QuickBooks Online or Xero.
- **Move from Harvest** with one token: people, clients, projects, rates, time, expenses and invoices, checked total by total against Harvest. A CSV import works offline.
- **Reports** for time, uninvoiced work, budgets and expenses, exported to CSV or Excel.
- **A browser extension** (Chrome, Firefox) with a timer and a "Track time" button on Jira, Asana, GitHub, Linear and Trello.
- **Your data stays yours:** a full export on every plan and in every state, a public REST API, and two-factor sign-in.

<p>
  <img src="docs/screenshots/report.png" alt="The time report" width="49%">
  <img src="docs/screenshots/invoice.png" alt="A draft invoice" width="49%">
</p>

## Our promises

These are enforced in the code, not only in the terms of service:

1. **No usage-based pricing.** Nothing is metered for billing. Honest Robin Cloud limits seats only, and the self-hosted edition has no limits.
2. **Export always works,** including on free and lapsed accounts.
3. **The self-hosted edition is complete:** the same code with the same features. A test fails if the editions differ.
4. **No dark patterns:** no hidden fees, no forced annual plans, and you cancel in the app.

## Run it yourself

You need Docker. The only other dependency, PostgreSQL 16, comes with it.

```sh
git clone https://github.com/honestrobin/time.git
cd time
docker compose -f deploy/docker-compose.yml up -d
```

Open http://localhost:8080. The first person to sign up becomes the admin; everyone else joins by
invitation. To run it on your own domain with HTTPS, and for settings, backups and upgrades, see
[docs/self-host.md](docs/self-host.md).

## Develop

You need JDK 21 (Gradle can download it), Node 22 with pnpm, and Docker or Podman for the test database.

```sh
pnpm install
./gradlew :backend:test                  # backend tests (Testcontainers)
./gradlew :backend:bootRun               # needs a Postgres: see docs/development.md
pnpm --filter @honestrobin/web dev       # the web app on :5173
```

| Path | What |
|---|---|
| `backend/` | Kotlin, Spring Boot 3, jOOQ, Flyway, PostgreSQL with row-level security |
| `frontend/` | React and TypeScript web app |
| `extension/` | Browser extension for Chrome and Firefox |
| `packages/api-client/` | TypeScript client generated from the OpenAPI document |
| `deploy/` | Dockerfile, Docker Compose and an HTTPS setup with Caddy |
| `docs/` | Specification, decision records, design philosophy (`design.md`), self-hosting guide, API docs |
| `e2e/` | Playwright end-to-end tests |

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## License and trademarks

The code is licensed under the [GNU AGPL v3](LICENSE), with no contributor licence agreement. The
name and the robin are not part of the licence; [TRADEMARKS.md](TRADEMARKS.md) says what's fine.

Claude, Anthropic, Harvest, Jira, Trello, Asana, GitHub, Linear, QuickBooks, Xero, Stripe, Paddle,
Storecove, Peppol and other names mentioned here are trademarks of their respective owners.
Honest Robin is not affiliated with or endorsed by any of them; they're named only to say what it
works with or was built with.
