# 0017. One address per product

- Status: accepted
- Date: 2026-10-04
- Spec reference: §9 (browser extension)

## Context

Honest Robin: Time is the first of several Honest Robin products, and Honest Robin Cloud needs an
address for each. Until now the browser extension assumed `app.honestrobin.com`.

## Decisions

1. **Each product gets its own subdomain, named after it.** Time lives at
   `https://time.honestrobin.com`.
2. **The extension's cloud address** (its default, and its one fixed host permission) is
   `https://time.honestrobin.com`.
3. **A sign-in shared by all products**, when there is one, gets a subdomain of its own (for
   example `account.honestrobin.com`), rather than putting the products under one address.

## Why

- Browsers keep cookies and stored data per subdomain, so one product can't read another's
  sessions, even through a bug.
- Each product can run on its own servers and be released on its own schedule.
- The app assumes it lives at the root of its address. Paths such as `/time` would need a base
  path in the web app, the API, cookies and the extension.
- Changing an extension's host permission after it's published makes every user approve the new
  address again. The extension isn't published yet, so changing it now costs nothing.

## Consequences

- Self-hosted instances are unaffected: people enter their own address in the extension.
- `app.honestrobin.com` is free for later use.
