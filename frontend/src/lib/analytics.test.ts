// SPDX-License-Identifier: AGPL-3.0-only
import { afterEach, describe, expect, it, vi } from "vitest";
import { sendPageView } from "./analytics";

const config = { posthog_key: "phc_test", posthog_host: "https://eu.i.posthog.com" };

describe("sendPageView", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("sends nothing without a key (self-hosted) or an account", () => {
    const send = vi.fn(() => Promise.resolve(new Response()));
    expect(sendPageView(null, "acc-1", "/time", send)).toBe(false);
    expect(sendPageView(config, null, "/time", send)).toBe(false);
    expect(send).not.toHaveBeenCalled();
  });

  it("sends the route pattern under the account's id, without cookies", () => {
    const send = vi.fn(() => Promise.resolve(new Response()));
    expect(sendPageView(config, "acc-1", "/invoices/$invoiceId", send)).toBe(true);
    const [url, init] = send.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("https://eu.i.posthog.com/capture/");
    expect(init.credentials).toBe("omit");
    const body = JSON.parse(init.body as string);
    expect(body).toMatchObject({ api_key: "phc_test", event: "$pageview", distinct_id: "acc-1" });
    expect(body.properties.$pathname).toBe("/invoices/$invoiceId");
  });

  it("respects Global Privacy Control and Do Not Track", () => {
    const send = vi.fn(() => Promise.resolve(new Response()));
    vi.stubGlobal("navigator", { ...navigator, globalPrivacyControl: true });
    expect(sendPageView(config, "acc-1", "/time", send)).toBe(false);
    vi.stubGlobal("navigator", { ...navigator, doNotTrack: "1" });
    expect(sendPageView(config, "acc-1", "/time", send)).toBe(false);
    expect(send).not.toHaveBeenCalled();
  });
});
