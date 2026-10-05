// SPDX-License-Identifier: AGPL-3.0-only
// "Cancel in the app, in two clicks" in the Robin's Code: a button, then the dialog's answer, which
// says plainly what happens. No guilt, no offers, and nothing changes before the period ends.
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import i18n from "../../i18n";
import type { Subscription } from "./billing";
import { CancelPlan } from "./CancelPlan";

// What GET /api/v1/billing/subscription answers for a yearly Team plan with three people.
const team: Subscription = {
  plan: "team",
  status: "active",
  seats_used: 3,
  invitations_pending: 0,
  seats_billed: 3,
  interval: "year",
  currency: "EUR",
  locked_unit_price_minor: 8400,
  current_period_end: "2027-10-01T00:00:00Z",
  billing_available: true,
  prices: [],
  free_plan_seats: 1,
};

/** Stands in for the server: answers every request with [answer], and records it. */
function server(answer: Subscription) {
  const fetch = vi.fn(async (_path: string, _init?: RequestInit) => new Response(JSON.stringify(answer), { status: 200, headers: { "Content-Type": "application/json" } }));
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

function show(s: Subscription) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <CancelPlan subscription={s} />
    </QueryClientProvider>,
  );
}

beforeAll(async () => {
  if (!i18n.isInitialized) await new Promise((done) => i18n.on("initialized", done));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("CancelPlan", () => {
  it("cancels in two clicks, at the end of the period, after saying plainly what happens", async () => {
    const fetch = server({ ...team, cancel_at: team.current_period_end });
    show(team);

    // Click one opens the dialog; nothing is sent yet.
    fireEvent.click(screen.getByRole("button", { name: "Cancel the Team plan" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/^Everything stays as it is until .*2027, the end of the period you've paid for\. The plan doesn't renew after that\.$/)).toBeTruthy();
    expect(within(dialog).getByText(/^Then the account moves to the free plan, for 1 person\. Now 3 people can sign in\./)).toBeTruthy();
    expect(within(dialog).getByText(/until you choose who stays/)).toBeTruthy();
    expect(within(dialog).getByText("Your data stays as it is, and export works on every plan.")).toBeTruthy();
    expect(within(dialog).getByText("Until then, you can keep the Team plan here with one click.")).toBeTruthy();
    // Two ways out of the dialog, and nothing else: no offer, no question about leaving.
    expect(within(dialog).getAllByRole("button").map((b) => b.textContent)).toEqual(["Close", "Cancel at the end of this period"]);
    expect(fetch).not.toHaveBeenCalled();

    // Click two cancels, once.
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel at the end of this period" }));
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    expect(fetch.mock.calls[0][0]).toBe("/api/v1/billing/cancellation");
    expect(fetch.mock.calls[0][1]?.method).toBe("POST");
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("closing the dialog cancels nothing", () => {
    const fetch = server(team);
    show(team);
    fireEvent.click(screen.getByRole("button", { name: "Cancel the Team plan" }));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("once cancelled, says when it ends, and keeps the plan in one click", async () => {
    const fetch = server(team);
    show({ ...team, cancel_at: "2027-10-01T00:00:00Z" });
    expect(screen.getByText(/^The Team plan ends on .*2027\. Until then, everything stays as it is\.$/)).toBeTruthy();
    expect(screen.getByText(/^It then renews on .*2027 as before, at your locked price of €84\.00 per person a year\.$/)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Keep the Team plan" }));
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    expect(fetch.mock.calls[0][0]).toBe("/api/v1/billing/cancellation");
    expect(fetch.mock.calls[0][1]?.method).toBe("DELETE");
  });
});
