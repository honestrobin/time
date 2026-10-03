# 0010. Renamed from Unbent to Honest Robin

- Status: accepted
- Date: 2026-10-03
- Spec reference: the product's working name

## Context

The spec used "Unbent Time" as a working name, pending a trademark and domain check. `unbent.com`, `.app`, `.io` and `.org` are taken, and `unbent.app` is a live app with the same name. On 2 October the maintainer chose **Honest Robin** for the suite, with products named "Honest Robin: Time", "Honest Robin: Notes" and so on, and registered `honestrobin.com`.

Nothing has been released or deployed, so the rename does not need to stay compatible with the old names.

## Decision

Everything was renamed in one commit, migrations included:

| What | Before | After |
|---|---|---|
| Product name in titles | Unbent Time | Honest Robin: Time |
| Name in sentences and emails | Unbent Time | Honest Robin |
| Hosted service | Unbent Cloud | Honest Robin Cloud |
| Kotlin package | `app.unbent` | `com.honestrobin.time` |
| Main classes | `UnbentApplication`, `UnbentProperties`, `UnbentPrincipal` | `HonestRobinApplication`, `HonestRobinProperties`, `HonestRobinPrincipal` |
| Settings | `UNBENT_*`, `unbent.*` | `HONESTROBIN_*`, `honestrobin.*` |
| npm packages | `@unbent/*` | `@honestrobin/*` |
| Account header | `Unbent-Account-Id` | `HonestRobin-Account-Id` |
| Session cookie | `unbent_session` | `honestrobin_session` |
| Personal access token prefix | `ubt_` | `hrt_` |
| Database functions, settings, role | `unbent_*`, `unbent.*`, `unbent_app` | `honestrobin_*`, `honestrobin.*`, `honestrobin_app` |
| Docker image, jar, compose project | `unbent-time` | `honestrobin-time` |
| Contact addresses | `security@unbent.app`, `conduct@unbent.app` | `security@honestrobin.com`, `conduct@honestrobin.com` |

The spec (`docs/spec.md`) and decision records 0001 to 0009 keep the old name, because they record what was decided at the time.

## Consequences

- A database created before the rename has the old function names and fails Flyway's checksum check. Create a new database; there are no deployments to migrate.
