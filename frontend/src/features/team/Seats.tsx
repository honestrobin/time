// SPDX-License-Identifier: AGPL-3.0-only
// Giving someone sign-in access on Honest Robin Cloud asks first. On the free plan: a Team
// subscription, with its price, one button away. On the Team plan: the price of one more person,
// billed from the moment they can sign in, never for an invitation nobody has accepted.
import { useMutation, useQuery } from "@tanstack/react-query";
import { useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, ConfirmDialog, SelectField, useToast } from "../../design";
import { errorInfo } from "../../lib/api";
import { formatDate, formatMoney } from "../../lib/format";
import { seatQuestion, type Interval, type SeatPrice, type SeatQuestion } from "../../lib/seats";
import { startTeamCheckout, subscriptionQuery } from "../settings/billing";

type Subscribe = Extract<SeatQuestion, { kind: "subscribe" }>;
type Confirm = Extract<SeatQuestion, { kind: "confirm" }>;

/** "€8.50 a month", "€84.00 a year": the price of one person for one billing period. */
function usePerPeriod() {
  const { t } = useTranslation();
  return (price: SeatPrice) => t(`team.seats.per.${price.interval}`, { price: formatMoney(price.unitPriceMinor, price.currency) });
}

/** What one more person costs, from when, and what is charged that day. */
export function SeatCost({ question, name }: { question: Confirm; name: string }) {
  const { t } = useTranslation();
  const { price } = question;
  const values = {
    name,
    price: t(`team.seats.billed.${price.interval}`, { price: formatMoney(price.unitPriceMinor, price.currency) }),
    unit: formatMoney(price.unitPriceMinor, price.currency),
  };
  return (
    <>
      {question.stale && <p className="notice notice-warn">{t("team.seats.stale")}</p>}
      <p>{t(question.startsNow ? "team.seats.costNow" : "team.seats.costWhenAccepted", values)}</p>
      {question.periodEnd && <p className="muted small">{t("team.seats.periodEnds", { date: formatDate(question.periodEnd.slice(0, 10), "long") })}</p>}
    </>
  );
}

/**
 * Asks an admin to say yes to the price of one more person, on its own button that names the
 * price; the request is sent again only from there. It reads the question from [error], and stays
 * open while the confirmed request runs ([busy]).
 */
export function ConfirmSeatDialog({
  error,
  busy,
  name,
  action,
  onCancel,
  onConfirm,
}: {
  error: unknown;
  busy: boolean;
  name: string;
  action: "invite" | "link" | "back";
  onCancel: () => void;
  onConfirm: (price: SeatPrice) => void;
}) {
  const { t } = useTranslation();
  const perPeriod = usePerPeriod();
  const asked = useMemo(() => {
    const q = seatQuestion(error);
    return q?.kind === "confirm" ? q : null;
  }, [error]);
  // While the confirmed request runs, its error is cleared: keep showing what was asked.
  const last = useRef<Confirm | null>(null);
  if (asked) last.current = asked;
  else if (!busy) last.current = null;
  const shown = asked ?? last.current;
  return (
    <ConfirmDialog
      open={shown !== null}
      onOpenChange={(o) => !o && !busy && onCancel()}
      title={t("team.seats.confirmTitle", { name })}
      body={shown && <SeatCost question={shown} name={name} />}
      confirmLabel={shown ? t(`team.seats.confirm.${action}`, { price: perPeriod(shown.price) }) : ""}
      busy={busy}
      danger={false}
      onConfirm={() => shown && onConfirm(shown.price)}
    />
  );
}

/**
 * The free plan is full: what a Team subscription costs (the billing page's list prices), what
 * checkout counts, the invitations already waiting and what each will cost once accepted, and one
 * button that opens the checkout. Nothing that already works stops.
 */
export function SubscribeNotice({ question }: { question: Subscribe }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [interval, choose] = useState<Interval>(question.prices[0]?.interval ?? "year");
  const [paid, setPaid] = useState(false);
  // Paddle tells us by webhook: after the checkout, look until the subscription shows.
  const sub = useQuery({ ...subscriptionQuery, refetchInterval: (q) => (paid && q.state.data?.plan !== "team" ? 2000 : false) });
  const ready = paid && sub.data?.plan === "team";
  const checkout = useMutation({
    mutationFn: () => startTeamCheckout(interval, () => setPaid(true)),
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const chosen = question.prices.find((p) => p.interval === interval);
  // One person for one billing period: a yearly price is paid for the whole year.
  const perPerson = chosen && formatMoney(chosen.perSeatPerMonthMinor * (chosen.interval === "year" ? 12 : 1), chosen.currency);
  const per = (amount: string) => t(`team.seats.per.${interval}`, { price: amount });
  const people = Math.max(1, sub.data?.seats_used ?? 1);
  const waiting = sub.data?.invitations_pending ?? 0;

  return (
    <div className="notice stack" role="status">
      <p>
        {t("team.seats.freeFull", { count: question.freeSeats })} {t("team.seats.perPerson")}
      </p>
      {ready ? (
        <p>{t("team.seats.ready")}</p>
      ) : paid ? (
        <p className="muted">{t("team.seats.waiting")}</p>
      ) : (
        <>
          {question.prices.length > 0 && (
            <SelectField
              label={t("team.seats.howToPay")}
              value={interval}
              onChange={choose}
              options={question.prices.map((p) => ({
                value: p.interval,
                label: t(`team.seats.option.${p.interval}`, {
                  price: formatMoney(p.perSeatPerMonthMinor, p.currency),
                  total: formatMoney(p.perSeatPerMonthMinor * 12, p.currency),
                }),
              }))}
            />
          )}
          {chosen && perPerson && (
            <p className="small">
              {t("team.seats.atCheckout", {
                count: people,
                total: per(formatMoney(chosen.perSeatPerMonthMinor * (chosen.interval === "year" ? 12 : 1) * people, chosen.currency)),
              })}
              {waiting > 0 && <> {t("team.seats.waitingInvitations", { count: waiting, price: per(perPerson) })}</>}
            </p>
          )}
          <div>
            <Button variant="primary" onClick={() => checkout.mutate()} busy={checkout.isPending}>
              {t("team.seats.subscribe")}
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
