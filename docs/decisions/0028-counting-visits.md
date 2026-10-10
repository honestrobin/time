# 0028. Page views are counted for every visit; anything more is opt-in

- Status: accepted (by the maintainer on 11 October 2026)
- Date: 2026-10-11
- Spec reference: §2 (PostHog), §13 (privacy)
- Supersedes: point 5 of 0015 ("Browsers that refuse tracking are respected")

## Context

0015 skipped page views when a browser sent Global Privacy Control or Do Not Track, because the
Robin's Code then promised it. Those signals were made against following people from site to
site and building profiles of them. A page view in Time does neither: it goes out without cookies,
under the account's id, with the route's pattern and nothing about the person. Skipping it only
made the counts wrong for the people most careful about their privacy.

The Robin's Code, article 5, now says instead what we count, and that any tracking beyond it is
off until someone turns it on.

## Decision

1. **Every signed-in page view is counted,** whatever the browser's tracking signals say, with
   everything else in 0015 unchanged: the cloud edition only, the account's id, the route's
   pattern, no cookies, no PostHog script, no location lookup.
2. **Nothing beyond that count without a choice.** Time sends no other tracking. If it ever does,
   it's off by default and someone turns it on for themselves.

## Consequences

`lib/analytics.ts` no longer reads `navigator.doNotTrack` or `navigator.globalPrivacyControl`.
The test that checked they stopped page views now checks that a page view under them carries only
what every page view carries. The ledger row and `docs/companies.md` say the same.
