# Honest Robin: Time

Guidance for AI coding assistants (and a quick orientation for anyone) working in this
repository. Time tracking for freelancers first, then small teams. `ROADMAP.md` says what's built and what's left; the specification is `docs/spec.md`, and
decisions are recorded in `docs/decisions/`. The rules every Honest Robin agent follows are in
[honestrobin/robinscode](https://github.com/honestrobin/robinscode); this file adds what's
particular to Time, and where the two disagree, stop and ask.

## What Time is now

A small core that's a joy to use, before anything else (decided 9 October 2026): track time (a
timer, day and week), clients and projects, one report (hours and amount by client and project,
with CSV), export and Move out, settings and help. Everything else is built and switched off by
default: invoices, payments, accounting, expenses, tasks, team, approvals, budgets and the import
(`platform.features`, `HONESTROBIN_FEATURES`, `frontend/src/lib/features.ts`). A switch only hides:
the API, the export and Move out stay complete, and both editions have the same switches. Nothing
new goes into the core, and nothing comes back on, until the maintainer says the core is a joy;
then one feature at a time, when a customer asks for it.

## Project rules

- **Honest Robin's rules come first.** They're in
  [honestrobin/robinscode](https://github.com/honestrobin/robinscode) (`rules/`), with the
  promises (`robins-code.md`), and they load in every session in the workspace. This file only adds
  what's particular to Time; it doesn't restate them, so the two can't drift apart.
- **Global by default.** No defaults, copy or compliance scoped to one country or region
  (decision record 0009). Country rules go in modules behind a neutral core.
- **The ledger.** `PROMISES.md` lists every promise Time makes and what keeps it today. A change
  that builds, breaks, renames or moves what a row points at updates that row in the same commit,
  and a test it names is never weakened, skipped or deleted to make a check pass: when one has to
  change, say so at the top of the pull request. Edition-specific code (cloud vs self-host)
  lives only in `billing`, `analytics` and `platform.edition`; `EditionParityTest` checks it.
- **The changelog.** A change people will notice adds a line under "Unreleased" in `CHANGELOG.md`,
  in their words, in the same pull request, so it's there before it reaches Honest Robin Cloud.
- **Nothing private in the repository:** it is public. No secrets, credentials or personal data,
  not even in tests (use stand-ins like `MockStripe`).
- Commits follow Conventional Commits, with a body that says why. Record decisions someone might
  later question in `docs/decisions/`.

## Commands

```sh
./gradlew :backend:test                                  # Testcontainers: needs Docker (or DOCKER_HOST for Podman)
HONESTROBIN_EDITION=cloud ./gradlew :backend:test        # the cloud edition
./gradlew :backend:jooqCodegen                           # after a new migration; commit backend/src/generated
./gradlew :backend:test --tests '*OpenApiExportTest' && pnpm --filter @honestrobin/api-client build   # after an API change
./gradlew :backend:checkLicense && node scripts/check-npm-licenses.mjs && bash scripts/check-license-headers.sh
pnpm -r typecheck && pnpm --filter '!@honestrobin/e2e' -r --if-present test
pnpm --filter @honestrobin/extension build:e2e           # before the extension e2e tests
cd e2e && HONESTROBIN_E2E_BASE_URL=http://localhost:5173 npx playwright test tests/10-m1-tour.spec.ts tests/20-extension.spec.ts
```

The e2e tests need the backend (`bootRun`, see `docs/development.md`) and Vite (`pnpm --filter
@honestrobin/web dev`). `00-first-user` needs an empty database. CI fails if the app logs any
`ERROR` during the e2e run.

## Things that bit before

- Tests share one `MutableClock` that other tests move: use wall-clock `Instant.now()` for
  scheduling, signatures and sessions; pin dates (`atMidday`) where a test depends on "today".
- Never hard-code a date where the app wants today (timers only run today): a test written that
  way passes on the day it's written and fails at midnight. Give the entry a duration instead.
- Kotlin default arguments on Spring-proxied (`@Transactional`) methods are evaluated on the
  proxy: use overloads.
- Every new table must be listed in `ExportFormat` (`TABLES` or `EXCLUDED`), or a test fails.
- jOOQ refuses a query outside a transaction (`QueryOutsideTransaction`, decision record 0027),
  in tests too: wrap it in `tx.run` or `tx.system`. In an after-commit callback those join the
  transaction that has just ended and are refused too; start a new one (`PROPAGATION_REQUIRES_NEW`,
  as `BillingService` does). What only the database user may do, such as `create role`, goes over
  plain JDBC.
- A migration that grants something to `honestrobin_app` adds it to `docs/self-host.md` (Database
  role) too: that's how a role created after a restore gets its rights.
- Keys between account tables include `account_id` (V15); a single-column reference to another
  account's row is refused by the database.
- `external_links.system` has a check constraint; add new systems in a migration.
- Radix Select can't hold an empty value: use `NONE` from `design/Select`.
- Chrome content scripts have no `customElements` registry.
- Never `pkill -f` with a pattern that appears in your own command line: it kills your shell.
