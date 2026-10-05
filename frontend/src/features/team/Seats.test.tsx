// SPDX-License-Identifier: AGPL-3.0-only
// "Limits are kept at the moment you act" in the Robin's Code: one more paid person is asked for
// first, with the price, and only a button of its own says yes.
import { ApiError } from "@honestrobin/api-client";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import i18n from "../../i18n";
import { ConfirmSeatDialog } from "./Seats";

// What SubscriptionSeatGate in BillingController.kt answers for an invitation on a yearly Team plan.
const oneMorePerson = new ApiError(409, {
  code: "seat_confirmation_required",
  message: "When they accept, you pay for them: EUR 84.00 a year, paid yearly. …",
  details: {
    unit_price_minor: 8400,
    currency: "EUR",
    interval: "year",
    billing_starts: "when_accepted",
    seats_billed: 1,
    current_period_end: "2027-10-01T00:00:00Z",
    stale: false,
  },
});

beforeAll(async () => {
  if (!i18n.isInitialized) await new Promise((done) => i18n.on("initialized", done));
});

afterEach(cleanup);

describe("ConfirmSeatDialog", () => {
  it("shows what one more person costs, and says yes only on its own button, which names the price", () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(<ConfirmSeatDialog error={oneMorePerson} busy={false} name="Tui" action="invite" onCancel={onCancel} onConfirm={onConfirm} />);
    expect(screen.getByText(/^When Tui accepts, you pay for them: €84\.00 a year, paid yearly\./)).toBeTruthy();
    expect(screen.getByText(/That day, Paddle charges for what's left of your current billing period, up to €84\.00\. Nothing is charged before they accept\.$/)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Invite for €84.00 a year" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith({ unitPriceMinor: 8400, currency: "EUR", interval: "year" });
  });

  it("asks nothing when the error is about something else", () => {
    render(
      <ConfirmSeatDialog
        error={new ApiError(409, { code: "already_active", message: "This person already has access" })}
        busy={false}
        name="Tui"
        action="invite"
        onCancel={vi.fn()}
        onConfirm={vi.fn()}
      />,
    );
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
