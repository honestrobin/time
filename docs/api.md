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
printed on invoices (the account's, or one invoice's, which also needs an admin). In the web app
they ask for your password if you signed in more than ten minutes ago. With a token, exports
work as usual; everything else in this list needs the web app. Changing where clients pay is
kept off tokens on purpose: a token can be obtained by tricking someone into approving a device
sign-in, and redirected payments are the costliest thing it could do.

Two-factor sign-in doesn't change how tokens work: a token is already a second credential. In
the web app, with two-factor sign-in on, `POST /api/v1/auth/login` (and the sign-in link, reset
and invitation endpoints) answer `{"two_factor_required": true, "challenge": "…"}` instead of
setting a session; `POST /api/v1/auth/two_factor` with the challenge and a `code` from the app
(or a `recovery_code`) finishes signing in. An account can require two-factor sign-in of everyone
(`require_two_factor` on `PATCH /api/v1/account`); until a member sets it up, their requests,
tokens included, answer `two_factor_required`.

**Signing in an app without a password** (how the browser extension does it, RFC 8628): `POST
/api/v1/auth/device` with a `client_name` returns a `device_code`, a `user_code` and a
`verification_uri_complete`. Show the user code and open the link; the person types the code in
the web app and approves it. The link carries no code, so nobody can be sent one that approves a
stranger's device in a click, and the approval page says when the request came from another network. Meanwhile poll `POST /api/v1/auth/device/token` with the device code every `interval`
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
| 402 | `account_read_only` (lapsed or scheduled for deletion; reading, exports and ending connections still work, and a lapsed account can deactivate people: see Seats), `subscription_required` (cloud; see Seats) |
| 403 | `forbidden`, `insufficient_scope`, `reauth_required` (web sessions: sign in again or confirm the password with `POST /api/v1/auth/reauth`), `two_factor_required` (the account requires two-factor sign-in, which this person hasn't set up) |
| 404 | `not_found` |
| 409 | `invoiced`, `entry_locked`, `week_submitted`, `week_approved`, `seat_confirmation_required` and `invitation_needs_team_plan` (cloud; see Seats), and others named after the conflict |
| 412 | `stale` (the `If-Match` ETag is out of date) |
| 422 | `validation_failed`, with `fields` |
| 429 | `invite_limit`, `invite_cooldown`, `invoice_email_limit`, `too_many_signups`, `too_many_attempts`, `import_running`, `import_limit` |

## Seats (Honest Robin Cloud)

On Honest Robin Cloud you pay for each person who can sign in. An invitation is free until it's
accepted. Four requests give someone sign-in access: `POST /api/v1/people` (with `send_invite`
left on), `POST /api/v1/people/{id}/invite` (also when sending an invitation again),
`POST /api/v1/people/{id}/invite_link`, and `PATCH /api/v1/people/{id}` with `"is_active": true`
for a deactivated person who could sign in or had an invitation. None of them adds to the bill
without a yes to the price first:

- **On the free plan,** a person beyond it is refused with `402 subscription_required`. `details`
  has `free_plan_seats` and the Team plan's list `prices` (`interval`, `currency`,
  `per_seat_per_month_minor`), the same ones `GET /api/v1/billing/subscription` shows.
- **On the Team plan,** the request is refused with `409 seat_confirmation_required`. `details`
  has the price of one more person: `unit_price_minor` and `currency` for each `interval`
  (`month`, or `year` for a price paid yearly), plus `billing_starts` (`when_accepted` for an
  invitation, `now` for someone coming back), `seats_billed`, `current_period_end` and `stale`.
  Show that price, and once it's agreed, send the same request again with it:
  `confirm_unit_price_minor`, `confirm_currency` and `confirm_interval`. If the price changed in
  between, the answer is the same 409 with the price now and `"stale": true`.

Nothing is charged when you say yes. The person is billed from the moment they can sign in: that
day, Paddle charges for what's left of the current billing period, at most one period's price,
and the full price at each renewal after that. Bringing back someone whose seat is still paid for
(they were deactivated less than 15 minutes ago) asks nothing, because nothing more is charged.
The self-hosted edition has no seats, so it never asks, and the `confirm_` parameters change
nothing there.

**Cancelling** (admins): `POST /api/v1/billing/cancellation` cancels the Team plan at the end of
the period that's paid for; nothing changes until then, and `cancel_at` in the answer says when.
While a payment is past due, as Paddle has it at that moment, that period isn't paid for, so the
plan ends at once instead. The body says which you agreed to: `{"ends": "period_end"}` (the
default) or `{"ends": "now"}`. If that's not what's true by then, the answer is
`409 cancellation_changed` with the one that is in `details.ends`, and nothing changed. Sending it
again after a cancellation at the period's end changes nothing; after the plan ended at once, the
answer is `409 no_subscription`. `DELETE /api/v1/billing/cancellation` takes a cancellation back
before that day, and the plan renews as before. If Paddle refuses (it takes no changes in the 30
minutes before a renewal), the answer is `409 billing_provider_refused` and nothing changed. If
Paddle doesn't answer, it's `502 billing_provider_unavailable`: we don't know yet whether Paddle
took the change, and the subscription shows it within a few minutes if it did.

**When a subscription ends** with more people who can sign in than the free plan holds, the
account turns read-only (`402 account_read_only`), and export keeps working. Its admins can still
deactivate people: `PATCH /api/v1/people/{id}` with `{"is_active": false}` and no other field.
Once no more people can sign in than `free_plan_seats` (in `GET /api/v1/billing/subscription`),
the account works again: at once when a deactivation gets it there, otherwise at the next run of
the billing job, which runs about every 15 minutes. A deactivated person's running timer stops at
the moment the account turned read-only. Starting Team again works too. While the subscription is
ended, accepting an invitation that the free plan has no room for is refused with
`409 invitation_needs_team_plan`, and nothing changes; the link keeps working until it expires.
An account waiting to be deleted can't deactivate people until the deletion is cancelled.

```sh
# 1. Ask: on the Team plan, the answer is 409 with the price in "details".
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Tui","email":"tui@example.com","role":"member"}' \
  https://your-instance.example/api/v1/people
# 2. Once the price is agreed, send it back with the same request.
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Tui","email":"tui@example.com","role":"member"}' \
  "https://your-instance.example/api/v1/people?confirm_unit_price_minor=8400&confirm_currency=EUR&confirm_interval=year"
```

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

Moving out does the same and also lists everything still connected to the account (tokens,
devices, invitations, Stripe, QuickBooks, Xero, Peppol, a Harvest sync or unfinished import,
invoice links, invoice reminders, and on Honest Robin Cloud a running subscription, with its
`plan`, `status` and `interval`). Each comes with `end_in`, the page in Time where an admin ends
it; invoice links have none, because they end only when the account is deleted. Moving out changes
nothing else in the account:

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" https://your-instance.example/api/v1/account/move_out
curl -H "Authorization: Bearer $TOKEN" https://your-instance.example/api/v1/account/connections
```

Ending a connection works in every state of an account, also while it's read-only: the
Disconnect endpoints of Stripe, QuickBooks, Xero and Peppol, stopping a Harvest sync or cancelling
an import, `DELETE /api/v1/account/api_tokens/{id}` (an admin revokes anyone's token or device)
and `DELETE /api/v1/people/{id}/invite` (withdraws an invitation).

## Stability

Paths are versioned (`/api/v1`). Within v1, fields and endpoints are added but not removed or
renamed, and error codes keep their meaning. A change that would break a client goes into v2,
announced in the release notes, with v1 kept alongside it for at least twelve months.
