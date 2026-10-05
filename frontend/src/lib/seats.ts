// SPDX-License-Identifier: AGPL-3.0-only
// Giving someone sign-in access on Honest Robin Cloud: the server asks first when it needs a Team
// subscription (402 subscription_required) or a new paid seat (409 seat_confirmation_required),
// and says what it costs. This reads those answers; the Team pages show them.
import { ApiError } from "@honestrobin/api-client";

export type Interval = "month" | "year";

export interface ListPrice {
  interval: Interval;
  currency: string;
  perSeatPerMonthMinor: number;
}

export type SeatQuestion =
  /** The free plan is full: a Team subscription first, at these list prices. */
  | { kind: "subscribe"; freeSeats: number; prices: ListPrice[] }
  /**
   * On the Team plan, one more person to pay for: [unitPriceMinor] per [interval], billed from the
   * moment they can sign in (now, or when they accept the invitation). Nothing is charged before.
   */
  | { kind: "confirm"; unitPriceMinor: number; currency: string; interval: Interval; startsNow: boolean };

const isInterval = (v: unknown): v is Interval => v === "month" || v === "year";

/** What the server asks before giving someone sign-in access, or null if the error is about something else. */
export function seatQuestion(error: unknown): SeatQuestion | null {
  if (!(error instanceof ApiError)) return null;
  const d: Record<string, unknown> = error.body.details ?? {};
  if (error.status === 402 && error.code === "subscription_required") {
    const listed: unknown[] = Array.isArray(d.prices) ? d.prices : [];
    const prices = listed.flatMap((p): ListPrice[] => {
      const { interval, currency, per_seat_per_month_minor: minor } = (p ?? {}) as Record<string, unknown>;
      return isInterval(interval) && typeof currency === "string" && typeof minor === "number" ? [{ interval, currency, perSeatPerMonthMinor: minor }] : [];
    });
    const { free_plan_seats: free } = d;
    return { kind: "subscribe", freeSeats: typeof free === "number" ? free : 1, prices };
  }
  if (error.status === 409 && error.code === "seat_confirmation_required") {
    const { unit_price_minor: price, currency, interval, billing_starts: starts } = d;
    if (typeof price !== "number" || typeof currency !== "string" || !isInterval(interval)) return null;
    return { kind: "confirm", unitPriceMinor: price, currency, interval, startsNow: starts === "now" };
  }
  return null;
}
