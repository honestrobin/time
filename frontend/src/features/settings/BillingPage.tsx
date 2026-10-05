// SPDX-License-Identifier: AGPL-3.0-only
// Honest Robin Cloud subscription (spec §14). Only the cloud edition has this page.
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, PageHeader, useToast } from "../../design";
import { errorInfo } from "../../lib/api";
import { formatDate, formatMoney } from "../../lib/format";
import { accountQuery } from "./AccountSettingsPage";
import { call, startTeamCheckout, subscriptionQuery } from "./billing";
import { CancelPlan } from "./CancelPlan";

export function BillingPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const sub = useQuery(subscriptionQuery);
  const { data: account } = useQuery(accountQuery);
  const [waiting, setWaiting] = useState(false);

  const checkout = useMutation({
    mutationFn: (interval: "month" | "year") =>
      startTeamCheckout(interval, () => {
        // Paddle tells us by webhook; give it a moment, then look again.
        setWaiting(true);
        setTimeout(() => void qc.invalidateQueries({ queryKey: ["billing"] }).then(() => setWaiting(false)), 4000);
      }),
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const portal = useMutation({
    mutationFn: () => call<{ url: string }>("POST", "/api/v1/billing/portal"),
    onSuccess: (p) => window.open(p.url, "_blank", "noopener"),
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  const s = sub.data;
  // Open invitations are shown, never billed: a person counts once they can sign in. After a Team
  // plan ended, one can't be accepted while the free plan is full, and the admin hears it here.
  const invitationsBlocked = s && (s.status === "canceled" || s.status === "paused") && s.seats_used >= s.free_plan_seats;
  const waitingInvitations = s && s.invitations_pending > 0 && (
    <p className="muted small">{t(invitationsBlocked ? "billing.invitationsBlocked" : "billing.invitationsWaiting", { count: s.invitations_pending })}</p>
  );
  return (
    <div className="page page-narrow">
      <PageHeader title={t("billing.title")} lead={t("billing.lead")} />
      {sub.error && <p className="notice notice-error">{errorInfo(sub.error).message}</p>}
      {s && !s.billing_available && <p className="notice">{t("billing.unavailable")}</p>}
      {s?.price_reports
        .filter((r) => !r.refunded)
        .map((r) => (
          // Paddle reported more than the locked price: the lock stays, and if Paddle charges more, the difference is refunded.
          <p key={`${r.reported_unit_price_minor}-${r.reported_at}`} className="notice notice-warn" role="status">
            {r.period_end && <>{t("billing.priceAboveLockPeriod", { date: formatDate(r.period_end.slice(0, 10), "long") })} </>}
            {t("billing.priceAboveLock", {
              reported: t(`billing.unitPrice.${r.interval}`, { price: formatMoney(r.reported_unit_price_minor, r.currency) }),
              locked: t(`billing.unitPrice.${r.interval}`, { price: formatMoney(r.locked_unit_price_minor, r.currency) }),
            })}
          </p>
        ))}
      {s && account?.status === "lapsed" && (
        // Read-only since the Team plan ended: the two ways back, and deactivating is free.
        <div className="notice notice-warn stack" role="status">
          <p>{t("billing.lapsed", { count: s.free_plan_seats })}</p>
          <p className="small">
            {t("billing.lapsedHow", { count: s.seats_used })} <Link to="/team">{t("billing.lapsedTeam")}</Link>
          </p>
        </div>
      )}
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
                  // The same count checkout sends to Paddle: the people who can sign in.
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
                      {s.invitations_pending > 0 && (
                        // Invitations already waiting: starting Team is the yes to them, so they're shown here, with what each adds.
                        <p className="muted small">
                          {t("billing.invitationsLater", {
                            count: s.invitations_pending,
                            price: t(`team.seats.per.${p.interval}`, {
                              price: formatMoney(p.per_seat_per_month_minor * (p.interval === "year" ? 12 : 1), p.currency),
                            }),
                          })}
                        </p>
                      )}
                      <Button variant={p.interval === "year" ? "primary" : "secondary"} onClick={() => checkout.mutate(p.interval)} busy={checkout.isPending && checkout.variables === p.interval}>
                        {t("billing.subscribe")}
                      </Button>
                    </div>
                  );
                })}
            </div>
          )}
          {waitingInvitations}
          {waiting && <p className="muted">{t("billing.waiting")}</p>}
        </section>
      )}
      {s && s.plan === "team" && (
        <section className="stack">
          <h2>{t("billing.teamTitle")}</h2>
          {s.status === "past_due" && <p className="notice notice-warn">{t("billing.pastDue")}</p>}
          <dl className="facts">
            <dt>{t("billing.seats")}</dt>
            <dd>
              {t("billing.seatsValue", { used: s.seats_used, billed: s.seats_billed ?? s.seats_used })}
              {waitingInvitations}
            </dd>
            {s.locked_unit_price_minor != null && s.currency && (
              <>
                <dt>{t("billing.price")}</dt>
                <dd>{t(`billing.unitPrice.${s.interval ?? "month"}`, { price: formatMoney(s.locked_unit_price_minor, s.currency) })}</dd>
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
            <p className="notice notice-ok">
              {t("billing.priceLock", { price: t(`billing.unitPrice.${s.interval ?? "month"}`, { price: formatMoney(s.locked_unit_price_minor, s.currency) }) })}
            </p>
          )}
          <p className="muted small">{t("billing.seatsHint")}</p>
          <div>
            <Button onClick={() => portal.mutate()} busy={portal.isPending}>
              {t("billing.manage")}
            </Button>
          </div>
          <CancelPlan subscription={s} />
        </section>
      )}
    </div>
  );
}
