# 0006. jOOQ classes are generated from migrations and committed

- Status: accepted
- Date: 2026-09-30
- Spec reference: §3.1

## Context

jOOQ code generation needs a live database. Requiring one for every build would hurt contributors and the Docker build.

## Decision

`./gradlew :backend:jooqCodegen` starts a throwaway Postgres (Testcontainers), applies the Flyway migrations and regenerates `backend/src/generated/jooq`. The output is committed, and CI regenerates it and fails on any diff.

## Consequences

Normal builds need no database. Schema changes need a regeneration step, which is documented in CONTRIBUTING.md.
