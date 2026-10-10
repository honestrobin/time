// SPDX-License-Identifier: AGPL-3.0-only
// Page views for Honest Robin Cloud (spec §2), sent only when the server hands out a PostHog key:
// never on self-hosted instances. No script from PostHog and nothing stored in the browser. What
// goes out is the route's pattern ("/invoices/$invoiceId"), not the address (a search can hold a
// client's name), under the account's id, as the server's funnel events use, and only while
// someone is signed in. That's all it counts (decision record 0028): a browser's Do Not Track or
// Global Privacy Control doesn't stop it, and nothing beyond it is sent. The request goes from the
// browser, so PostHog sees its IP address; it's asked not to look up a location from it
// ($geoip_disable), and the project should discard IPs (setting).

export interface AnalyticsConfig {
  posthog_key: string;
  posthog_host: string;
}

/** Sends one page view; returns whether it did. */
export function sendPageView(
  config: AnalyticsConfig | null | undefined,
  accountId: string | null,
  route: string,
  send: typeof fetch = fetch,
): boolean {
  if (!config || !accountId) return false;
  const body = JSON.stringify({
    api_key: config.posthog_key,
    event: "$pageview",
    distinct_id: accountId,
    timestamp: new Date().toISOString(),
    properties: { $pathname: route, $current_url: location.origin + route, account_id: accountId, $process_person_profile: false, $geoip_disable: true },
  });
  // text/plain keeps it a simple request (no preflight); keepalive lets it finish while leaving.
  void send(`${config.posthog_host}/capture/`, { method: "POST", body, keepalive: true, credentials: "omit", headers: { "Content-Type": "text/plain" } }).catch(
    () => undefined,
  );
  return true;
}
