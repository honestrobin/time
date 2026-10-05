// SPDX-License-Identifier: AGPL-3.0-only
// Giving someone sign-in access on Honest Robin Cloud asks first. On the free plan: a Team
// subscription, with its price, one button away. On the Team plan: the price of one more person,
// billed from the moment they can sign in, never for an invitation nobody has accepted.
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, ConfirmDialog, SelectField, useToast } from "../../design";
import { errorInfo } from "../../lib/api";
import { formatMoney } from "../../lib/format";
import type { Interval, SeatQuestion } from "../../lib/seats";
import { startTeamCheckout, subscriptionQuery } from "../settings/billing";

type Subscribe = Extract<SeatQuestion, { kind: "subscribe" }>;
type Confirm = Extract<SeatQuestion, { kind: "confirm" }>;

/** What one more person costs, and from when. */
export function SeatCost({ question, name }: { question: Confirm; name: string }) {
  const { t } = useTranslation();
  const price = t(`team.seats.price.${question.interval}`, { price: formatMoney(question.unitPriceMinor, question.currency) });
  return <>{t(question.startsNow ? "team.seats.costNow" : "team.seats.costWhenAccepted", { name, price })}</>;
}

/** Asks an admin to agree to the price of one more person before the request is sent again. */
export function ConfirmSeatDialog({
  question,
  name,
  confirmLabel,
  busy,
  onCancel,
  onConfirm,
}: {
  question: SeatQuestion | null;
  name: string;
  confirmLabel: string;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const { t } = useTranslation();
  const ask = question?.kind === "confirm" ? question : null;
  return (
    <ConfirmDialog
      open={ask !== null}
      onOpenChange={(o) => !o && onCancel()}
      title={t("team.seats.confirmTitle", { name })}
      body={ask && <p><SeatCost question={ask} name={name} /></p>}
      confirmLabel={confirmLabel}
      busy={busy}
      danger={false}
      onConfirm={onConfirm}
    />
  );
}

/**
 * The free plan is full: what a Team subscription costs (the billing page's list prices), and one
 * button that opens its checkout. Nothing that already works stops.
 */
export function SubscribeNotice({ question }: { question: Subscribe }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [interval, choose] = useState<Interval>(question.prices[0]?.interval ?? "year");
  const [paid, setPaid] = useState(false);
  // Paddle tells us by webhook: after the checkout, look until the subscription shows.
  const sub = useQuery({ ...subscriptionQuery, enabled: paid, refetchInterval: (q) => (q.state.data?.plan === "team" ? false : 2000) });
  const ready = paid && sub.data?.plan === "team";
  const checkout = useMutation({
    mutationFn: () => startTeamCheckout(interval, () => setPaid(true)),
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

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
                label: t("team.seats.option", { interval: t(`billing.interval.${p.interval}`), price: formatMoney(p.perSeatPerMonthMinor, p.currency) }),
              }))}
            />
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
