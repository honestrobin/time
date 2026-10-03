# 0015. Product analytics (Honest Robin Cloud only)

- Status: accepted
- Date: 2026-10-03
- Spec reference: §2 (PostHog), §13 (privacy), AT-6.5

## Decisions

1. **Cloud only, and only when configured.** The server sends funnel events (sign-up, import,
   first timer, first invoice sent, invoice paid online, subscription) and hands the web app a
   PostHog project key through `/api/v1/auth/config` only in the cloud edition with
   `honestrobin.posthog.api-key` set. Self-hosted instances send nothing and give the browser no
   key; a test checks both editions.
2. **The account is the distinct id.** Page views and funnel events use the account's id, so a
   funnel reads per workspace. No names, emails, amounts or client data go out.
3. **Route patterns, not addresses.** A page view carries `/invoices/$invoiceId`, never the URL
   the person sees, whose query can hold a client's name (report filters, searches).
4. **No PostHog script, nothing stored in the browser.** The web app posts the event itself
   (`lib/analytics.ts`): no cookies, no local storage, no session recording, no autocapture of
   clicks or form fields. The Content-Security-Policy allows the PostHog host for `connect-src`
   only, and only where a key is configured.
5. **Browsers that refuse tracking are respected.** With Global Privacy Control or Do Not Track
   on, the web app sends no page views. (Server-side funnel events still record that an account
   reached a step; they hold no personal data beyond the account id.)
6. **EU region.** The default host is `https://eu.i.posthog.com`.

## Consequences

The privacy policy names PostHog as a subprocessor for Honest Robin Cloud and describes the
above; it needs no cookie banner for analytics, since nothing is stored on the device. Legal
review should confirm that reading before launch.
