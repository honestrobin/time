# 0015. Product analytics (Honest Robin Cloud only)

- Status: accepted; points 1 (the funnel), 2, 5 and, for page views, 7's link to the account id superseded by 0028 (11 October 2026)
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
4. **No PostHog script, nothing stored for analytics.** The web app posts the event itself
   (`lib/analytics.ts`): no cookies or local storage of its own, no session recording, no
   autocapture of clicks or form fields. Page views are sent only while someone is signed in. The Content-Security-Policy allows the PostHog host for `connect-src`
   only, and only where a key is configured.
5. **Browsers that refuse tracking are respected.** With Global Privacy Control or Do Not Track
   on, the web app sends no page views. (Server-side funnel events still record that an account
   reached a step; they hold no personal data beyond the account id.)
6. **EU region.** The default host is `https://eu.i.posthog.com`.
7. **The browser's IP address reaches PostHog.** Page views go from the browser, so PostHog sees
   its IP address and user agent, linked to the account id. Events ask PostHog not to look up a
   location (`$geoip_disable`), and the PostHog project must have "Discard client IP data" on;
   the code can't enforce that setting. (Added 4 October 2026: the first version of this record
   left it out.)

## Consequences

Honest Robin Cloud's privacy policy, still to be written, must name PostHog as a subprocessor and
describe the above, IP addresses included. Whether page views need consent is for legal review
before launch: nothing is stored on the device for them, but they use the account id the app
keeps in local storage, and the browser's IP address goes to a third party. Routing page views
through our own server, without the client's IP address, would settle the IP question.
