# 0028. Count visits by default; everything else only by choice

- Status: accepted (by the maintainer on 11 October 2026)
- Date: 2026-10-11
- Spec reference: §2 (PostHog), §13 (privacy), AT-6.5
- Supersedes: in 0015, point 1 for the funnel (events now need the account's choice), point 2
  for page views (they no longer carry the account's id), point 5 (Do Not Track and Global
  Privacy Control no longer stop page views), and in point 7, that page views are linked to the
  account id

## Context

The Robin's Code, article 5, now says: by default we only count visits, without cookies, without
following anyone to other sites, and never who they are. Anything more, such as how an account
uses a product, is off until someone turns it on; it helps us decide what to build next.

Time sent two things to PostHog: page views under the account's id, and funnel events (the steps
an account reaches) for every account in the cloud. Both said how an account uses Time.

## Decision

1. **Page views count visits, nothing more.** Each carries a fresh random id, the route's pattern
   and nothing about the account or the person. A browser's Do Not Track or Global Privacy Control
   doesn't stop them: counting a visit isn't following anyone.
2. **The funnel only by choice.** The steps an account reaches are still recorded in its own data
   (they're in its export) and sent to PostHog only if the account shares how it uses Time
   (`UsageSharing`). The setting to choose that isn't built yet, so for now no account shares
   anything. When it's built: off by default, asked at most once, both answers equally easy.

Everything else in 0015 stands: the cloud edition only, no PostHog script, nothing stored in the
browser for analytics, no location lookup, the EU region.

## Consequences

- We lose the funnel until the setting exists, and see visits rather than accounts.
- The steps an account reaches are still recorded (`account_milestones`) and exported, though
  nothing sends them now. When the setting is built, a test that what's shared carries no names,
  email addresses or workspace names comes back, with one that one admin's choice is clear about
  covering the whole team.
- The browser still sends page views itself, so PostHog sees its IP address (0015, point 7).
  Counting them on our own server instead would end that, and settle whether reading anything
  from the browser for analytics needs consent.
