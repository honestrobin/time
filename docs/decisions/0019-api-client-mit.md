# 0019. The API client is MIT

- Status: accepted
- Date: 2026-10-05
- Spec reference: §1.2.5

## Context

The specification licenses everything in the repository under the AGPL (§1.2.5, decision record
0001). The Robin's Code, which every Honest Robin product keeps, says the AGPL is for what runs as a
server and the MIT licence for libraries that run inside other people's software.
`packages/api-client` is such a library: a typed client for the public REST API, generated from
`openapi.json`, for anyone who builds on Time. Under the AGPL, a program that includes it would
have to be AGPL too. That's more than the Code asks, and it keeps people from building on the API.

## Decision

`packages/api-client` is licensed under MIT: its `package.json`, its own `LICENSE` file and the
SPDX header of its source. Everything else stays `AGPL-3.0-only`. The licence header check expects
MIT under `packages/api-client` and the AGPL everywhere else. One person holds all the copyright
today, so the change needs nobody else's consent.

## Consequences

- Anyone can put the client in software under any licence, open or closed.
- The server, the web app and the browser extension stay AGPL, as the Code says for what runs as a
  server or as an app.
- A future library package that runs inside other people's software starts as MIT, with the header
  check extended to its folder in the same change.
