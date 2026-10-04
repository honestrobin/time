# Development

## Backend

```sh
# a throwaway Postgres on port 5433
docker run -d --name honestrobin-pg -e POSTGRES_USER=honestrobin -e POSTGRES_PASSWORD=honestrobin -e POSTGRES_DB=honestrobin -p 5433:5432 postgres:18-alpine
HONESTROBIN_DB_URL=jdbc:postgresql://localhost:5433/honestrobin HONESTROBIN_SIGNUP_MODE=open ./gradlew :backend:bootRun
```

Tests use Testcontainers (`./gradlew :backend:test`). Set `HONESTROBIN_EDITION=cloud` to run the suite in the cloud edition. To use an existing Postgres instead of a container, set `HONESTROBIN_TEST_JDBC_URL` (plus `HONESTROBIN_TEST_JDBC_USER`/`_PASSWORD`).

After changing a migration: `./gradlew :backend:jooqCodegen`.
After changing an API: `./gradlew :backend:test --tests '*OpenApiExportTest' && pnpm --filter @honestrobin/api-client build`.

## Frontend

```sh
pnpm install
pnpm --filter @honestrobin/web dev   # http://localhost:5173, proxies /api to :8080
```

## End-to-end

```sh
docker compose -f deploy/docker-compose.yml up -d --build
pnpm --filter @honestrobin/e2e test
```

`00-first-user.spec.ts` covers AT-0.1 and needs a fresh database.

Sign-ups per network are rate-limited in the database (`rate_limit_events`), so the limit
survives restarts. On a development database used for many test sign-ups, clear it with
`delete from rate_limit_events where bucket like 'signup:%';`.
