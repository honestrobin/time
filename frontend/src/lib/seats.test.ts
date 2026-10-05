// SPDX-License-Identifier: AGPL-3.0-only
import { ApiError } from "@honestrobin/api-client";
import { describe, expect, it } from "vitest";
import { seatQuestion } from "./seats";

// The bodies BillingController.kt sends (SubscriptionSeatGate).
const freePlanFull = new ApiError(402, {
  code: "subscription_required",
  message: "The free plan is for one person. …",
  details: {
    free_plan_seats: 1,
    prices: [
      { interval: "year", currency: "EUR", per_seat_per_month_minor: 700 },
      { interval: "month", currency: "EUR", per_seat_per_month_minor: 850 },
    ],
  },
});

const newSeat = (billingStarts: "when_accepted" | "now") =>
  new ApiError(409, {
    code: "seat_confirmation_required",
    message: "When they accept, your plan goes up by one person: …",
    details: { unit_price_minor: 850, currency: "EUR", interval: "month", billing_starts: billingStarts, seats_billed: 3 },
  });

describe("seatQuestion", () => {
  it("shows the Team prices when the free plan is full", () => {
    expect(seatQuestion(freePlanFull)).toEqual({
      kind: "subscribe",
      freeSeats: 1,
      prices: [
        { interval: "year", currency: "EUR", perSeatPerMonthMinor: 700 },
        { interval: "month", currency: "EUR", perSeatPerMonthMinor: 850 },
      ],
    });
  });

  it("asks before an invitation that adds a paid seat, billed once it's accepted", () => {
    expect(seatQuestion(newSeat("when_accepted"))).toEqual({ kind: "confirm", unitPriceMinor: 850, currency: "EUR", interval: "month", startsNow: false });
  });

  it("asks before bringing someone back, billed from that moment", () => {
    expect(seatQuestion(newSeat("now"))).toMatchObject({ kind: "confirm", startsNow: true });
  });

  it("never asks to confirm a price it can't show", () => {
    const priceless = new ApiError(409, { code: "seat_confirmation_required", message: "…", details: { currency: "EUR" } });
    expect(seatQuestion(priceless)).toBeNull();
  });

  it("leaves every other error alone", () => {
    expect(seatQuestion(new ApiError(409, { code: "already_active", message: "This person already has access" }))).toBeNull();
    expect(seatQuestion(new ApiError(402, { code: "account_read_only", message: "…" }))).toBeNull();
    expect(seatQuestion(new Error("offline"))).toBeNull();
  });
});
