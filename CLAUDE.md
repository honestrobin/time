# Honest Robin: Time

Guidance for AI coding assistants (and a quick orientation for anyone) working in this
repository. Time tracking, invoicing and reports for small teams, with the workflows Harvest users
know. `ROADMAP.md` says what's built and what's left; the specification is `docs/spec.md`, and
decisions are recorded in `docs/decisions/`.

## Project rules

- **Global by default.** No defaults, copy or compliance scoped to one country or region
  (decision record 0009). Country rules go in modules behind a neutral core.
- **Plain English** in the UI, emails and docs: short sentences, no jargon.
- **The trust charter** (README, "Our promises") is enforced in code: nothing is metered for
  billing, export always works, and the editions have the same features. Edition-specific code
  (cloud vs self-host) lives only in `billing`, `analytics` and `platform.edition`;
  `EditionParityTest` enforces it.
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
- Kotlin default arguments on Spring-proxied (`@Transactional`) methods are evaluated on the
  proxy: use overloads.
- Every new table must be listed in `ExportFormat` (`TABLES` or `EXCLUDED`), or a test fails.
- Keys between account tables include `account_id` (V15); a single-column reference to another
  account's row is refused by the database.
- `external_links.system` has a check constraint; add new systems in a migration.
- Radix Select can't hold an empty value: use `NONE` from `design/Select`.
- Chrome content scripts have no `customElements` registry.
- Never `pkill -f` with a pattern that appears in your own command line: it kills your shell.
