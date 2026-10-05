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

/** The price of one seat for each billing period, as the admin sees it and says yes to. */
export interface SeatPrice {
  unitPriceMinor: number;
  currency: string;
  interval: Interval;
}

export type SeatQuestion =
  /** The free plan is full: a Team subscription first, at these list prices. */
  | { kind: "subscribe"; freeSeats: number; prices: ListPrice[] }
  /**
   * On the Team plan, one more person to pay for, at [price], billed from the moment they can sign
   * in (now, or when they accept the invitation). Nothing is charged before. [stale]: the price
   * sent with the request wasn't the price now.
   */
  | { kind: "confirm"; price: SeatPrice; startsNow: boolean; periodEnd: string | null; stale: boolean };

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
    const { unit_price_minor: unit, currency, interval, billing_starts: starts, current_period_end: end, stale } = d;
    if (typeof unit !== "number" || typeof currency !== "string" || !isInterval(interval)) return null;
    return {
      kind: "confirm",
      price: { unitPriceMinor: unit, currency, interval },
      startsNow: starts === "now",
      periodEnd: typeof end === "string" ? end : null,
      stale: stale === true,
    };
  }
  return null;
}

/** The query parameters that say yes to [price] for a new paid seat; none without one. */
export function confirmQuery(price: SeatPrice | null): { confirm_unit_price_minor?: number; confirm_currency?: string; confirm_interval?: string } {
  return price ? { confirm_unit_price_minor: price.unitPriceMinor, confirm_currency: price.currency, confirm_interval: price.interval } : {};
}
