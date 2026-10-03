# 0016. Browser extension and live updates (M4)

- Status: accepted (selectors VERIFY against the live sites before release)
- Date: 2026-10-03
- Spec reference: §9, AT-4.1, AT-4.2

## Decisions

1. **Device flow for signing in** (RFC 8628). The extension can't use the web app's cookies, and
   self-hosted instances live at addresses no extension can list in advance, so OAuth redirects
   and `externally_connectable` are out. The device flow works for any address: the extension
   shows a code, the person approves it in the web app (with two-factor sign-in and the
   recent-sign-in rule for creating tokens), and the extension collects a personal access token
   for the account chosen. Codes use consonants only and expire after ten minutes; only a hash of
   the device's secret is stored, and the token is made when collected, once. Pasting a token
   remains for people who prefer it.
2. **Live updates over server-sent events, fed by Postgres.** A trigger on `time_entries` sends
   `NOTIFY` with the membership on commit (duplicates within a transaction fold into one); each
   server listens on its own connection and forwards to the streams it holds. That meets AT-4.1
   (within 2 s, tested) with several servers and no message broker. Only the visible browser tab
   keeps a stream open, so many tabs don't exhaust HTTP/1.1 connections; streams reconnect with
   backoff and refresh once on reconnect. Proxies are told not to buffer (`X-Accel-Buffering: no`)
   and hear a comment every 25 seconds.
3. **One content script, driven by data.** `extension/src/selectors.json` describes each site:
   URL patterns, title selectors, anchors with placements, all with fallbacks. Instances serve it at
   `/extension/selectors.json`; the extension takes the higher version. Manifest V3 forbids remote
   code, not remote data, so markup changes on the five sites can be fixed without a store review.
4. **The background makes all API calls.** Content scripts' requests count as the host page's, so
   they would need CORS on our side; messages to the background avoid that and keep the token out
   of pages entirely. The button lives in a shadow root (no custom-element class: content scripts
   have no `customElements` registry in Chrome).
5. **No new API for timers.** The extension uses the public API like any client: assignments for
   the picker, `POST /time_entries` without a duration to start, `/stop`, `/me/timer`.

## Consequences

The CSRF filter skips the two device endpoints (no cookies are involved). The e2e suite loads the
extension into Chromium and drives it against fixture pages for all five sites, routed under their
real addresses so the content script's matches apply.
