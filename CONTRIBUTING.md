# Contributing to Honest Robin: Time

Thanks for helping. A few ground rules keep the project healthy.

## Licence and sign-off

Honest Robin: Time is licensed under the GNU AGPL v3 (`AGPL-3.0-only`). We do **not** ask for a contributor licence agreement: you keep your copyright, so nobody can publish your contribution under another licence without your consent. One limit, stated plainly: today one person holds all the copyright, so future versions could still be relicensed until other contributors hold copyright too. What is already published stays AGPL.

Instead, every commit must carry a [Developer Certificate of Origin](https://developercertificate.org/) sign-off:

```
Signed-off-by: Your Name <you@example.com>
```

`git commit -s` adds it for you. CI rejects pull requests with unsigned commits.

Every source file starts with an SPDX header, for example `// SPDX-License-Identifier: AGPL-3.0-only`. The API client in `packages/api-client` is MIT instead, because it runs inside other people's software (decision record 0019). Dependencies must have AGPL-compatible licences, and CI checks this too.

## Commits

We use [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`), with a body that says why.

## Before you open a pull request

- `./gradlew :backend:test` passes. CI runs the suite in both editions (`HONESTROBIN_EDITION=selfhost` and `cloud`).
- `pnpm -r typecheck && pnpm --filter '!@honestrobin/e2e' -r --if-present test` passes.
- If you changed an API, run `./gradlew :backend:test --tests '*OpenApiExportTest'` and `pnpm --filter @honestrobin/api-client build`, then commit the regenerated client.
- If you changed a migration, run `./gradlew :backend:jooqCodegen` and commit the regenerated classes.
- UI strings go in `frontend/src/locales/en/*.json`, never inline.

## Project rules that are not up for debate

The [trust charter](README.md#our-promises) is enforced in code:

- No code may meter projects, clients, tasks, invoices or entries for billing.
- Export must work for every account in every state.
- The edition flag (`HONESTROBIN_EDITION`) may only switch Paddle billing, PostHog analytics, Honest Robin-operated provider credentials and marketing links. `EditionParityTest` enforces this.

Design decisions and deviations from the spec are recorded in `docs/decisions/` as short ADRs. Add one when you make a decision that someone might later ask "why?" about.

## Using Podman instead of Docker

Testcontainers works with rootless Podman:

```sh
podman system service --time=0 unix:///tmp/podman.sock &
export DOCKER_HOST=unix:///tmp/podman.sock TESTCONTAINERS_RYUK_DISABLED=true
```
