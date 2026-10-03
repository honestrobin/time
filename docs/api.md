# Honest Robin Time API

Everything the app does goes through this API, so anything you can do in the app you can do
from a script. The full reference, generated from the code, is at `/developers` in the app
(and as OpenAPI 3 at `/v3/api-docs`, and in `packages/api-client/openapi.json`). This page
covers what the reference can't: how to sign in, and the conventions every endpoint follows.

The API is the same in both editions. Honest Robin Cloud adds only its billing endpoints.

## Signing in

Create a personal access token under **Profile → API tokens**. A token:

- belongs to you **in one account**, and can do what you can do there, no more;
- has the scopes `read` (GET only) and/or `write`;
- can have an expiry date, and can be revoked at any time;
- is shown once. It starts with `hrt_`; only a hash is stored.

Send it as a bearer token:

```sh
curl -H "Authorization: Bearer hrt_…" https://your-instance.example/api/v1/me
```

A few actions need more than a session cookie: creating API tokens, deleting the account,
starting a full export, connecting Stripe, and changing the bank details or payment instructions
printed on invoices. In the web app they ask for your password if you signed in more than ten
minutes ago. With a token, exports and bank details work as usual; creating tokens, deleting
the account and connecting Stripe need the web app.

Two-factor sign-in doesn't change how tokens work: a token is already a second credential. In
the web app, with two-factor sign-in on, `POST /api/v1/auth/login` (and the sign-in link, reset
and invitation endpoints) answer `{"two_factor_required": true, "challenge": "…"}` instead of
setting a session; `POST /api/v1/auth/two_factor` with the challenge and a `code` from the app
(or a `recovery_code`) finishes signing in. An account can require two-factor sign-in of everyone
(`require_two_factor` on `PATCH /api/v1/account`); until a member sets it up, their requests,
tokens included, answer `two_factor_required`.

**Signing in an app without a password** (how the browser extension does it, RFC 8628): `POST
/api/v1/auth/device` with a `client_name` returns a `device_code`, a `user_code` and a
`verification_uri_complete`. Show the user code and open the link; the person approves it in the
web app. Meanwhile poll `POST /api/v1/auth/device/token` with the device code every `interval`
seconds: it answers `authorization_pending` until approved, then once with a token (scopes read
and write) for the account they chose.

**Live updates**: `GET /api/v1/me/events` is a stream of server-sent events for your time entries
in the current account: `ready` when it opens, then `time_entries` whenever one changes (refresh
what you show). Streams end after 30 minutes; reconnect.

Requests with a token need no CSRF header. (The web app uses a session cookie plus the
`X-XSRF-TOKEN` header instead.) The `HonestRobin-Account-Id` header is optional with a token,
since the token already names its account; with a session it picks which of your accounts a
request is for.

## Conventions

**JSON in snake_case.** Dates are `YYYY-MM-DD` in the account's time zone (a time entry belongs to
the day it was worked, where it was worked); timestamps are ISO 8601 in UTC.

**Money is an integer in minor units**, next to its currency: `"total": 12550, "currency": "EUR"`
is €125.50, and `"total": 5000, "currency": "JPY"` is ¥5,000. Each currency's number of decimals
follows ISO 4217. Amounts in different currencies are never added together.

**Durations are integer seconds.** `rounded_seconds` applies the account's rounding (as on
invoices); `duration_seconds` is what was tracked.

**Rates and amounts are only in responses for people who may see them.** For someone without rate
permission, the fields are left out entirely rather than set to null.

**Lists page with a cursor.** List endpoints take `limit` and return
`{"data": [...], "next_cursor": "…"}`; pass `cursor=<next_cursor>` for the next page, until
`next_cursor` is null. Report filters that take several values repeat the parameter:
`?project_id=…&project_id=…`.

**Updates are PATCH with only the fields you change.** A field you send as `null` is cleared; a
field you leave out stays as it is.

**Optimistic locking, if you want it.** Single-item responses carry an `ETag`. Send it back as
`If-Match` on PATCH or DELETE and the change is refused with `412` if someone changed the item in
the meantime. Without `If-Match` the last write wins.

**Errors** have one shape:

```json
{ "code": "validation_failed", "message": "Please check the highlighted fields", "fields": { "email": "Enter a valid email address" } }
```

`code` is stable and meant for programs; `message` is meant for people. Common codes:

| Status | Codes |
|---|---|
| 400 | `bad_request`, `bad_cursor`, `invalid_link`, `invalid_password`, `invalid_code`, `challenge_expired`, `not_an_export`, `export_damaged`, `export_too_large` |
| 401 | `unauthenticated` |
| 402 | `account_read_only` (lapsed or scheduled for deletion; reading and exports still work), `subscription_required` (cloud) |
| 403 | `forbidden`, `insufficient_scope`, `reauth_required` (web sessions: sign in again or confirm the password with `POST /api/v1/auth/reauth`), `two_factor_required` (the account requires two-factor sign-in, which this person hasn't set up) |
| 404 | `not_found` |
| 409 | `invoiced`, `entry_locked`, `week_submitted`, `week_approved`, and others named after the conflict |
| 412 | `stale` (the `If-Match` ETag is out of date) |
| 422 | `validation_failed`, with `fields` |
| 429 | `invite_limit`, `invite_cooldown`, `invoice_email_limit`, `too_many_signups`, `too_many_attempts`, `import_running`, `import_limit` |

## Examples

Start a timer, then stop it:

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"project_id":"…","task_id":"…","spent_date":"2026-10-03"}' \
  https://your-instance.example/api/v1/time_entries
curl -X POST -H "Authorization: Bearer $TOKEN" https://your-instance.example/api/v1/time_entries/<id>/stop
```

Hours per project last month, and the same as a spreadsheet:

```sh
curl -H "Authorization: Bearer $TOKEN" \
  "https://your-instance.example/api/v1/reports/time?group_by=project&from=2026-09-01&to=2026-09-30"
curl -H "Authorization: Bearer $TOKEN" -o september.xlsx \
  "https://your-instance.example/api/v1/reports/time/export?format=xlsx&group_by=project&from=2026-09-01&to=2026-09-30"
```

Everything in the account, as one zip (admins; see [export-format.md](export-format.md)):

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" https://your-instance.example/api/v1/exports
```

## Stability

Paths are versioned (`/api/v1`). Within v1, fields and endpoints are added but not removed or
renamed, and error codes keep their meaning. A change that would break a client goes into v2,
announced in the release notes, with v1 kept alongside it for at least twelve months.
