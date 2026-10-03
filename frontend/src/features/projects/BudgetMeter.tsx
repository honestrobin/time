// SPDX-License-Identifier: AGPL-3.0-only
import { useTranslation } from "react-i18next";
import { formatDuration, formatMoney, formatPercent, type DurationStyle } from "../../lib/format";
import type { Budget } from "./queries";

/** Budget usage in display form. Money figures are absent for people who can't see rates. */
export function budgetFigures(usage: Budget, durationStyle: DurationStyle) {
  const money = usage.measure === "money";
  const budget = money ? usage.budget_amount : usage.budget_seconds;
  const spent = (money ? usage.spent_amount : usage.spent_seconds) as number | undefined;
  const remaining = money ? usage.remaining_amount : usage.remaining_seconds;
  const fmt = (v: number | null | undefined) =>
    v === null || v === undefined ? "" : money ? formatMoney(v, usage.currency) : formatDuration(v, durationStyle);
  return {
    money,
    hasBudget: budget !== undefined && budget !== null,
    canShow: spent !== undefined,
    budgetValue: budget ?? null,
    spentValue: spent ?? 0,
    budget: fmt(budget),
    spent: fmt(spent),
    remaining: fmt(remaining === undefined || remaining === null ? null : Math.abs(remaining)),
    over: remaining !== undefined && remaining !== null && remaining < 0,
  };
}

/**
 * The `.meter` bar: green while under the alert percentage, amber from it, red over budget.
 */
export function Meter({ spent, budget, alertPercent, label }: { spent: number; budget: number | null | undefined; alertPercent?: number | null; label?: string }) {
  const { t } = useTranslation();
  if (budget === null || budget === undefined) return null;
  const pct = budget > 0 ? (spent / budget) * 100 : spent > 0 ? 101 : 0;
  const state = pct > 100 ? "over" : alertPercent !== null && alertPercent !== undefined && pct >= Number(alertPercent) ? "warn" : "";
  const text = t("projects.budget.percentUsed", { percent: formatPercent(pct / 100) });
  return (
    <div
      className={["meter", state].filter(Boolean).join(" ")}
      role="meter"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(Math.min(pct, 100))}
      aria-valuetext={text}
      aria-label={label ?? text}
    >
      <span style={{ width: `${Math.min(pct, 100)}%` }} />
    </div>
  );
}

export function BudgetMeter({ usage, alertPercent, compact }: { usage: Budget; alertPercent?: number | null; compact?: boolean }) {
  const money = usage.measure === "money";
  const budget = money ? usage.budget_amount : usage.budget_seconds;
  const spent = (money ? usage.spent_amount : usage.spent_seconds) as number | undefined;
  if (spent === undefined) return null;
  const meter = <Meter spent={spent} budget={budget} alertPercent={alertPercent} />;
  return compact ? meter : <div className="budget-meter">{meter}</div>;
}
