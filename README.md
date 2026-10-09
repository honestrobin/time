# Honest Robin: Time

Open-source time tracking for freelancers first, and small teams next. Run it on your own
server, or use Honest Robin Cloud (coming later). It's one of the
[Honest Robin](https://honestrobin.com) products: a joy to use, honest, and you come first.

> **Not released yet.** Version 1 is built and being tried out before a first release. See the
> [roadmap](ROADMAP.md) for what's built, what isn't, and what's still to check.
>
> **A small core first.** Time shows a small core: tracking time, clients and projects, reports,
> and your data. Invoices, e-invoicing, the team, approvals, budgets, expenses and the Harvest
> import are built too, and switched off by default while we make the core a joy to use. They come
> back one at a time. A self-hosted instance can switch them on now with `HONESTROBIN_FEATURES`
> ([self-host.md](docs/self-host.md)).
>
> Screenshots come back with the new design of the core: the old ones showed every part switched
> on, which isn't what you'd see.

## Built by an AI, in the open

Honest Robin: Time is the first Honest Robin product, and it shows how they're made: by an AI,
given a clear brief and good habits. The code, tests and documentation were written by **Claude**
(Anthropic's model, working in [Claude Code](https://claude.com/claude-code)). A human maintainer
wrote the [product specification](docs/spec.md), made the product decisions and kept it on course.

How it's made is in the open:

- **Every commit Claude wrote** says so, in a co-author line. The only others are automatic
  dependency updates. The first commit gathers the work on version 1; the work since is in the
  history, change by change.
- **Every decision** someone might question is written down in [`docs/decisions/`](docs/decisions/),
  with the reasoning.
- **The code is tested:** backend tests in both editions, frontend and extension tests, and
  end-to-end tests that run the packaged app and the browser extension in a real browser. The
  Harvest import compares hours, billable amounts and invoice totals with
  Harvest's own reports (so far with a stand-in for Harvest's API, not a real account), and
  e-invoices are checked against the official validation rules.

Before a release the maintainer will test the product, review the full architecture and read the
code that matters most: invoices and payments, your data, and who can see what. AI agents make
most of the corrections and check our claims against the facts. He approves only the parts he
has checked. That review isn't done yet, and neither is an independent security audit, so treat
Time as pre-release software: try it with made-up data, and don't run your business on it yet.
Reports of anything that looks wrong are very welcome; see
[SECURITY.md](SECURITY.md).

## What it does

- **Track time** with a timer, or in day and week timesheets.
- **Clients and projects,** with an hourly rate if you want one.
- **Reports** of hours and amounts by client and project, exported to CSV or Excel.
- **A browser extension** (Chrome, Firefox) with a timer and a "Track time" button on Jira, Asana, GitHub, Linear and Trello.
- **Your data stays yours:** one Move out button, on every plan and in every state, gives you a full export, says where you can go next and lists what's still connected. A public REST API, and two-factor sign-in.

Built, and switched off by default (`HONESTROBIN_FEATURES`):

- **Invoices** from tracked time and expenses, with gapless numbering, taxes or VAT, PDFs, reminders, and online payment through your own Stripe account (no fee from us).
- **E-invoicing:** Factur-X/ZUGFeRD, XRechnung and Peppol BIS, sent over Peppol if you like; invoices and payments can go to QuickBooks Online or Xero.
- **Move from Harvest** with a personal access token and your account ID: people, clients, projects, rates, time, expenses and invoices. The import then compares hours, billable amounts and invoice totals with Harvest's own reports. A CSV import works offline.
- **Teams:** invitations, approvals that lock submitted weeks, budgets that warn you before they run out, expenses, and a shared list of tasks. The team shows by itself while an account has other people.

Switched off, a part also stops what it would do unasked (reminders, alerts, approval locks, a Harvest sync); its data stays.

## Our promises

What we promise, and what holds us to it:

1. **No usage-based pricing.** Nothing is metered for billing. Honest Robin Cloud's plans differ only in seats, and the self-hosted edition has no plan limits. Where anyone can sign up, daily mail limits guard against abuse; they're never billed.
2. **Export always works,** including on free and lapsed accounts.
3. **The self-hosted edition is complete:** the same code with the same features. Tests fail if the editions' endpoints differ, apart from paying for Honest Robin Cloud, or if code outside billing, analytics and the edition switch tells them apart.
4. **No dark patterns:** no hidden fees, no forced annual plans, and you cancel Honest Robin Cloud in the app, in two clicks. Not in the 30 minutes before a renewal: Paddle takes no changes then, the app says so, and a renewal charged after that needs a person.

Tests guard the first three, and the monthly-or-yearly choice and cancelling in the fourth, so a
change that breaks them by accident fails. Billing is tested against a stand-in for Paddle, not Paddle itself. Code
can't stop us breaking a promise on purpose, though, so all four will also go into the terms of service. And the last
guarantee is yours: you can export everything and run the same software yourself.

They sit on top of the [Robin's Code](https://honestrobin.com/code), the promises every Honest
Robin product is measured against. It's a draft until it's in the terms of service.
[`PROMISES.md`](PROMISES.md) goes through every one of them and says what keeps it today: a test,
code without a test, how we work, or nothing yet.

Every company that touches your data on Honest Robin Cloud is [listed](docs/companies.md), with
its country and what it sees.

## Run it yourself

You need Docker. The only other dependency, PostgreSQL 18, comes with it.

```sh
git clone https://github.com/honestrobin/time.git
cd time
docker compose -f deploy/docker-compose.yml up -d
```

Open http://localhost:8080. The first person to sign up becomes the admin, with the setup code the
app writes to its log (`docker compose -f deploy/docker-compose.yml logs app | grep "setup code"`),
so nobody else can claim a new instance. Everyone else joins by invitation. To run it on your own domain with HTTPS, and for settings, backups and upgrades, see
[docs/self-host.md](docs/self-host.md).

## Develop

You need JDK 25 (Gradle can download it), Node 24 with pnpm, and Docker or Podman for the test database.

```sh
pnpm install
./gradlew :backend:test                  # backend tests (Testcontainers)
./gradlew :backend:bootRun               # needs a Postgres: see docs/development.md
pnpm --filter @honestrobin/web dev       # the web app on :5173
```

| Path | What |
|---|---|
| `backend/` | Kotlin, Spring Boot 4, jOOQ, Flyway, PostgreSQL with row-level security |
| `frontend/` | React and TypeScript web app |
| `extension/` | Browser extension for Chrome and Firefox |
| `packages/api-client/` | TypeScript client generated from the OpenAPI document |
| `deploy/` | Dockerfile, Docker Compose and an HTTPS setup with Caddy |
| `docs/` | A tour of the architecture (`architecture.md`), specification, decision records, design philosophy (`design.md`), self-hosting guide, API docs |
| `e2e/` | Playwright end-to-end tests |

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## License and trademarks

The code is licensed under the [GNU AGPL v3](LICENSE), with no contributor licence agreement. The
API client in `packages/api-client` is [MIT](packages/api-client/LICENSE), so it can go inside
software under any licence. The name and the robin are not part of the licence; [TRADEMARKS.md](TRADEMARKS.md) says what's fine.

Claude, Anthropic, Harvest, Jira, Trello, Asana, GitHub, Linear, QuickBooks, Xero, Stripe, Paddle,
Storecove, Peppol and other names mentioned here are trademarks of their respective owners.
Honest Robin is not affiliated with or endorsed by any of them; they're named only to say what it
works with or was built with.
