// SPDX-License-Identifier: AGPL-3.0-only
import { afterEach, describe, expect, it, vi } from "vitest";
import { sendPageView } from "./analytics";

const config = { posthog_key: "phc_test", posthog_host: "https://eu.i.posthog.com" };

/** The request's body, without the two fields that change every time. */
function sent(send: ReturnType<typeof vi.fn>): { body: Record<string, unknown>; init: RequestInit; url: string } {
  const [url, init] = send.mock.calls[0] as unknown as [string, RequestInit];
  const { timestamp, distinct_id, ...body } = JSON.parse(init.body as string);
  expect(typeof timestamp).toBe("string");
  expect(distinct_id).toMatch(/^[0-9a-f-]{36}$/);
  return { body, init, url };
}

describe("sendPageView", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("sends nothing without a key (self-hosted) or an account", () => {
    const send = vi.fn(() => Promise.resolve(new Response()));
    expect(sendPageView(null, "acc-1", "/time", send)).toBe(false);
    expect(sendPageView(config, null, "/time", send)).toBe(false);
    expect(send).not.toHaveBeenCalled();
  });

  it("counts the visit by the route pattern, without cookies, and never the account", () => {
    const send = vi.fn(() => Promise.resolve(new Response()));
    expect(sendPageView(config, "acc-1", "/invoices/$invoiceId", send)).toBe(true);
    const { body, init, url } = sent(send);
    expect(url).toBe("https://eu.i.posthog.com/capture/");
    expect(init.credentials).toBe("omit");
    expect(body).toEqual({
      api_key: "phc_test",
      event: "$pageview",
      properties: {
        $pathname: "/invoices/$invoiceId",
        $current_url: location.origin + "/invoices/$invoiceId",
        $process_person_profile: false,
        $geoip_disable: true,
      },
    });
    expect(init.body as string).not.toContain("acc-1");
  });

  it("gives every view its own random id, so views can't be joined up", () => {
    const send = vi.fn(() => Promise.resolve(new Response()));
    sendPageView(config, "acc-1", "/time", send);
    sendPageView(config, "acc-1", "/time", send);
    const ids = send.mock.calls.map((call) => JSON.parse((call as unknown as [string, RequestInit])[1].body as string).distinct_id);
    expect(ids[0]).not.toBe(ids[1]);
  });

  it("counts the visit the same way when the browser asks not to be tracked", () => {
    const send = vi.fn(() => Promise.resolve(new Response()));
    vi.stubGlobal("navigator", { ...navigator, globalPrivacyControl: true, doNotTrack: "1" });
    expect(sendPageView(config, "acc-1", "/time", send)).toBe(true);
    expect(sent(send).body).toEqual({
      api_key: "phc_test",
      event: "$pageview",
      properties: { $pathname: "/time", $current_url: location.origin + "/time", $process_person_profile: false, $geoip_disable: true },
    });
  });
});
