// SPDX-License-Identifier: AGPL-3.0-only
// Honest Robin Cloud subscription (spec §14). Only the cloud edition has this page.
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, PageHeader, useToast } from "../../design";
import { ApiError, authHeaders, errorInfo } from "../../lib/api";
import { formatDate, formatMoney } from "../../lib/format";

// The billing endpoints exist in the cloud edition only, so they're not in the generated client
// (which describes the self-hosted API); these types mirror BillingController.kt.
interface PlanPrice {
  interval: "month" | "year";
  currency: string;
  per_seat_per_month_minor: number;
}
interface Subscription {
  plan: "free" | "team";
  status: string;
  seats_used: number;
  seats_billed?: number;
  interval?: "month" | "year";
  currency?: string;
  locked_unit_price_minor?: number;
  current_period_end?: string;
  cancel_at?: string;
  billing_available: boolean;
  prices: PlanPrice[];
}
interface Checkout {
  environment: string;
  client_token: string;
  price_id: string;
  quantity: number;
  email: string;
  custom_data: Record<string, string>;
}

async function call<T>(method: "GET" | "POST", path: string, body?: unknown): Promise<T> {
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

const subscriptionQuery = { queryKey: ["billing", "subscription"], queryFn: () => call<Subscription>("GET", "/api/v1/billing/subscription") };

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

async function openCheckout(c: Checkout, onDone: () => void) {
  const paddle = await loadPaddle();
  if (c.environment === "sandbox") paddle.Environment.set("sandbox");
  paddle.Initialize({ token: c.client_token, eventCallback: (e) => e.name === "checkout.completed" && onDone() });
  paddle.Checkout.open({ items: [{ priceId: c.price_id, quantity: c.quantity }], customer: { email: c.email }, customData: c.custom_data });
}

export function BillingPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const sub = useQuery(subscriptionQuery);
  const [waiting, setWaiting] = useState(false);

  const checkout = useMutation({
    mutationFn: (interval: "month" | "year") => call<Checkout>("POST", "/api/v1/billing/checkout", { interval }),
    onSuccess: (c) =>
      openCheckout(c, () => {
        // Paddle tells us by webhook; give it a moment, then look again.
        setWaiting(true);
        setTimeout(() => void qc.invalidateQueries({ queryKey: ["billing"] }).then(() => setWaiting(false)), 4000);
      }).catch((e: Error) => toast(e.message, "error")),
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const portal = useMutation({
    mutationFn: () => call<{ url: string }>("POST", "/api/v1/billing/portal"),
    onSuccess: (p) => window.open(p.url, "_blank", "noopener"),
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  const s = sub.data;
  return (
    <div className="page page-narrow">
      <PageHeader title={t("billing.title")} lead={t("billing.lead")} />
      {sub.error && <p className="notice notice-error">{errorInfo(sub.error).message}</p>}
      {s && !s.billing_available && <p className="notice">{t("billing.unavailable")}</p>}
      {s && s.plan === "free" && (
        <section className="stack">
          <h2>{t("billing.freeTitle")}</h2>
          <p>{t("billing.freeBody")}</p>
          {s.billing_available && (
            <div className="plan-options">
              {s.prices
                .slice()
                .reverse()
                .map((p) => {
                  const seats = Math.max(1, s.seats_used);
                  return (
                    <div key={p.interval} className="panel stack">
                      <h3>{t(`billing.interval.${p.interval}`)}</h3>
                      <p className="plan-price">
                        {t("billing.perSeat", { price: formatMoney(p.per_seat_per_month_minor, p.currency) })}
                      </p>
                      <p className="muted small">
                        {t(p.interval === "year" ? "billing.totalYear" : "billing.totalMonth", {
                          count: seats,
                          total: formatMoney(p.per_seat_per_month_minor * seats * (p.interval === "year" ? 12 : 1), p.currency),
                        })}
                      </p>
                      <Button variant={p.interval === "year" ? "primary" : "secondary"} onClick={() => checkout.mutate(p.interval)} busy={checkout.isPending && checkout.variables === p.interval}>
                        {t("billing.subscribe")}
                      </Button>
                    </div>
                  );
                })}
            </div>
          )}
          {waiting && <p className="muted">{t("billing.waiting")}</p>}
        </section>
      )}
      {s && s.plan === "team" && (
        <section className="stack">
          <h2>{t("billing.teamTitle")}</h2>
          {s.status === "past_due" && <p className="notice notice-warn">{t("billing.pastDue")}</p>}
          <dl className="facts">
            <dt>{t("billing.seats")}</dt>
            <dd>{t("billing.seatsValue", { used: s.seats_used, billed: s.seats_billed ?? s.seats_used })}</dd>
            {s.locked_unit_price_minor != null && s.currency && (
              <>
                <dt>{t("billing.price")}</dt>
                <dd>{t("billing.perSeat", { price: formatMoney(s.locked_unit_price_minor, s.currency) })}</dd>
              </>
            )}
            {s.interval && (
              <>
                <dt>{t("billing.billed")}</dt>
                <dd>{t(`billing.interval.${s.interval}`)}</dd>
              </>
            )}
            {s.cancel_at ? (
              <>
                <dt>{t("billing.ends")}</dt>
                <dd>{formatDate(s.cancel_at.slice(0, 10), "long")}</dd>
              </>
            ) : (
              s.current_period_end && (
                <>
                  <dt>{t("billing.renews")}</dt>
                  <dd>{formatDate(s.current_period_end.slice(0, 10), "long")}</dd>
                </>
              )
            )}
          </dl>
          {s.locked_unit_price_minor != null && s.currency && (
            <p className="notice notice-ok">{t("billing.priceLock", { price: formatMoney(s.locked_unit_price_minor, s.currency) })}</p>
          )}
          <p className="muted small">{t("billing.seatsHint")}</p>
          <div>
            <Button onClick={() => portal.mutate()} busy={portal.isPending}>
              {t("billing.manage")}
            </Button>
          </div>
        </section>
      )}
    </div>
  );
}
