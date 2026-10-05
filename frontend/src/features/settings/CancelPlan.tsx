// SPDX-License-Identifier: AGPL-3.0-only
// "Cancel in the app, in two clicks" (the Robin's Code): a button, then the dialog's answer.
// Nothing changes until the end of the period that's paid for, and until then the plan can be kept.
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Dialog, DialogActions, useToast } from "../../design";
import { errorInfo } from "../../lib/api";
import { formatDate, formatMoney } from "../../lib/format";
import { call, subscriptionQuery, type Subscription } from "./billing";

const day = (iso?: string) => (iso ? formatDate(iso.slice(0, 10), "long") : undefined);

export function CancelPlan({ subscription: s }: { subscription: Subscription }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const show = (next: Subscription) => qc.setQueryData(subscriptionQuery.queryKey, next);
  const cancel = useMutation({
    mutationFn: () => call<Subscription>("POST", "/api/v1/billing/cancellation"),
    onSuccess: (next) => {
      show(next);
      setOpen(false);
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const keep = useMutation({
    mutationFn: () => call<Subscription>("DELETE", "/api/v1/billing/cancellation"),
    onSuccess: show,
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const free = s.free_plan_seats;
  // What happens once the free plan starts: who may stay, and that the admin chooses.
  const after = (
    <p>
      {t("billing.cancel.after", { count: free })}
      {s.seats_used > free && <> {t("billing.cancel.choose", { count: free, used: s.seats_used })}</>}
    </p>
  );

  if (s.cancel_at) {
    const price =
      s.locked_unit_price_minor != null && s.currency
        ? t(`billing.unitPrice.${s.interval ?? "month"}`, { price: formatMoney(s.locked_unit_price_minor, s.currency) })
        : undefined;
    return (
      <div className="notice stack" role="status">
        <p>{t("billing.cancel.scheduled", { date: day(s.cancel_at) })}</p>
        {after}
        <div>
          <Button onClick={() => keep.mutate()} busy={keep.isPending}>
            {t("billing.cancel.keep")}
          </Button>
        </div>
        {price && <p className="muted small">{t("billing.cancel.keepHint", { date: day(s.cancel_at), price })}</p>}
      </div>
    );
  }

  const ends = day(s.current_period_end);
  return (
    <>
      <div>
        <Button onClick={() => setOpen(true)}>{t("billing.cancel.button")}</Button>
      </div>
      <Dialog open={open} onOpenChange={(o) => !cancel.isPending && setOpen(o)} title={t("billing.cancel.title")}>
        <div className="stack">
          <p>{ends ? t("billing.cancel.until", { date: ends }) : t("billing.cancel.untilEnd")}</p>
          {after}
          <p>{t("billing.cancel.data")}</p>
          <p>{t("billing.cancel.undo")}</p>
        </div>
        <DialogActions>
          <Button onClick={() => setOpen(false)} disabled={cancel.isPending}>
            {t("app.close")}
          </Button>
          <Button variant="primary" busy={cancel.isPending} onClick={() => cancel.mutate()}>
            {t("billing.cancel.confirm")}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
