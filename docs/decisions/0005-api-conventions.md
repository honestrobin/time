# 0005. One API for the SPA and third parties; snake_case JSON

- Status: accepted
- Date: 2026-09-30
- Spec reference: §10

## Context

The SPA needs an API, and there is also a public API. Two separate APIs would drift apart.

## Decision

- The SPA uses the public `/api/v1` API with a session cookie and CSRF header. Scripts and the extension use personal access tokens (`Authorization: Bearer ubt_…`), which skip CSRF.
- The account is addressed with the `HonestRobin-Account-Id` header. If a user belongs to exactly one account, the header is optional. Tokens are bound to one membership.
- JSON uses snake_case (familiar to Harvest API users, and it eases a P2 Harvest-compatible facade).
- The OpenAPI 3.1 document is exported by `OpenApiExportTest` to `packages/api-client/openapi.json`, and the TypeScript client is generated from it. Both are committed, and CI checks that they are up to date.
- Errors look like `{ "code": "...", "message": "...", "fields": { ... } }`.

## Consequences

Every UI feature is automatically available to API users. Rate and amount redaction (AT-1.5) has to be enforced centrally in serialisation, not in UI code.
