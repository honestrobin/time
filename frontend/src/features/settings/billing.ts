// SPDX-License-Identifier: AGPL-3.0-only
// Honest Robin Cloud subscription (spec §14): the billing page and the Team page both start the
// Team checkout from here.
import { ApiError, authHeaders } from "../../lib/api";

// The billing endpoints exist in the cloud edition only, so they're not in the generated client
// (which describes the self-hosted API); these types mirror BillingController.kt.
export interface PlanPrice {
  interval: "month" | "year";
  currency: string;
  per_seat_per_month_minor: number;
}
/** Paddle reported a price per seat above the lock: the lock stays, and if Paddle charges more, the difference is refunded. */
export interface PriceReport {
  reported_unit_price_minor: number;
  /** The locked price when Paddle reported it. */
  locked_unit_price_minor: number;
  currency: string;
  interval: "month" | "year";
  /** The end of the billing period it was reported for. */
  period_end?: string;
  reported_at: string;
  /** A person has refunded the difference and marked it so. */
  refunded: boolean;
}
export interface Subscription {
  plan: "free" | "team";
  status: string;
  /** People who can sign in: what checkout and Paddle count. */
  seats_used: number;
  /** Invitations not yet accepted; not billed. */
  invitations_pending: number;
  seats_billed?: number;
  interval?: "month" | "year";
  currency?: string;
  locked_unit_price_minor?: number;
  current_period_end?: string;
  cancel_at?: string;
  billing_available: boolean;
  prices: PlanPrice[];
  /** How many people the free plan has room for: a lapsed account works again once no more than this can sign in. */
  free_plan_seats: number;
  /** Prices Paddle reported above the lock, refunded or not; usually none. */
  price_reports: PriceReport[];
}
interface Checkout {
  environment: string;
  client_token: string;
  price_id: string;
  quantity: number;
  email: string;
  custom_data: Record<string, string>;
}

export async function call<T>(method: "GET" | "POST" | "DELETE", path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    credentials: "include",
    headers: { ...authHeaders(), ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => ({ code: `http_${res.status}`, message: res.statusText }));
  if (!res.ok) throw new ApiError(res.status, json);
  return json as T;
}

export const subscriptionQuery = { queryKey: ["billing", "subscription"], queryFn: () => call<Subscription>("GET", "/api/v1/billing/subscription") };

interface PaddleGlobal {
  Environment: { set: (env: string) => void };
  Initialize: (opts: { token: string; eventCallback?: (e: { name?: string }) => void }) => void;
  Checkout: { open: (opts: { items: { priceId: string; quantity: number }[]; customer: { email: string }; customData: Record<string, string> }) => void };
}

/** Loads Paddle.js once; the checkout runs in Paddle's overlay, so card details never touch us. */
function loadPaddle(): Promise<PaddleGlobal> {
  const w = window as unknown as { Paddle?: PaddleGlobal };
  if (w.Paddle) return Promise.resolve(w.Paddle);
  return new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = "https://cdn.paddle.com/paddle/v2/paddle.js";
    s.onload = () => (w.Paddle ? resolve(w.Paddle) : reject(new Error("Paddle didn't load")));
    s.onerror = () => reject(new Error("Paddle couldn't be reached"));
    document.head.appendChild(s);
  });
}

/**
 * Opens Paddle's checkout for a Team subscription. Paddle shows the amount, with any tax, before
 * anything is charged; [onDone] runs once the payment went through.
 */
export async function startTeamCheckout(interval: "month" | "year", onDone: () => void): Promise<void> {
  const c = await call<Checkout>("POST", "/api/v1/billing/checkout", { interval });
  const paddle = await loadPaddle();
  if (c.environment === "sandbox") paddle.Environment.set("sandbox");
  paddle.Initialize({ token: c.client_token, eventCallback: (e) => e.name === "checkout.completed" && onDone() });
  paddle.Checkout.open({ items: [{ priceId: c.price_id, quantity: c.quantity }], customer: { email: c.email }, customData: c.custom_data });
}
