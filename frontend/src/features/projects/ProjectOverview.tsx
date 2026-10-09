// SPDX-License-Identifier: AGPL-3.0-only
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { errorInfo } from "../../lib/api";
import { useFeature } from "../../lib/features";
import { formatDate, formatDuration, formatMoney, formatPercent, type DurationStyle } from "../../lib/format";
import { usePermissions } from "../../lib/session";
import { BudgetMeter, budgetFigures, Meter } from "./BudgetMeter";
import { isLineBudget } from "./labels";
import { budgetQuery, useAccountDefaults, type Budget, type Project } from "./queries";

export function ProjectOverview({ project, onEditSettings }: { project: Project; onEditSettings: () => void }) {
  const { t } = useTranslation();
  const perms = usePermissions();
  const { durationStyle } = useAccountDefaults();
  // Without the budgets feature the first panel shows only what was tracked (lib/features).
  const budgets = useFeature("budgets");
  const team = useFeature("team");
  const budgetQ = useQuery(budgetQuery(project.id));
  const usage = budgetQ.data;
  const currency = project.client.currency;
  const money = (v: number | null | undefined) => (v === null || v === undefined ? "" : formatMoney(v, currency));

  const dates =
    project.starts_on && project.ends_on
      ? t("projects.overview.dateRange", { from: formatDate(project.starts_on), to: formatDate(project.ends_on) })
      : project.starts_on
        ? t("projects.overview.startsOn", { date: formatDate(project.starts_on) })
        : project.ends_on
          ? t("projects.overview.endsOn", { date: formatDate(project.ends_on) })
          : null;

  const billing = !project.is_billable
    ? t("projects.overview.notBillable")
    : project.bill_by === "none"
      ? t("projects.billBy.none")
      : t(`projects.billBy.${project.bill_by}`);

  return (
    <div className="overview-grid">
      <section>
        <div className="section-head">
          <h2>{budgets ? t("projects.overview.budget") : t("projects.overview.trackedTitle")}</h2>
          {budgets && usage?.is_monthly && usage.period_start && (
            <span className="muted">{t("projects.overview.thisMonth", { date: formatDate(usage.period_start) })}</span>
          )}
        </div>
        {budgetQ.isError ? (
          <p className="notice notice-error">{errorInfo(budgetQ.error).message}</p>
        ) : !usage ? (
          <p className="muted">{t("app.loading")}</p>
        ) : (
          <BudgetSummary project={project} usage={usage} durationStyle={durationStyle} onEditSettings={onEditSettings} budgets={budgets} />
        )}
      </section>

      <section>
        <h2 style={{ marginBottom: 12 }}>{t("projects.overview.details")}</h2>
        <dl className="facts">
          <dt>{t("projects.fields.client")}</dt>
          <dd>{project.client.name}</dd>
          {project.code && (
            <>
              <dt>{t("projects.fields.code")}</dt>
              <dd>{project.code}</dd>
            </>
          )}
          <dt>{t("projects.overview.billing")}</dt>
          <dd>{billing}</dd>
          {project.is_billable && project.bill_by === "project" && perms.canSeeRates && (
            <>
              <dt>{t("projects.fields.hourlyRate")}</dt>
              <dd>{project.hourly_rate != null ? money(project.hourly_rate) : <span className="muted">{t("projects.overview.noRate")}</span>}</dd>
            </>
          )}
          {project.is_fixed_fee && (
            <>
              <dt>{t("projects.fields.fixedFee")}</dt>
              <dd>{perms.canSeeRates && project.fee_amount != null ? money(project.fee_amount) : t("app.yes")}</dd>
            </>
          )}
          {budgets && (
            <>
              <dt>{t("projects.fields.budgetBy")}</dt>
              <dd>
                {t(`projects.budgetBy.${project.budget_by}`)}
                {project.budget_by !== "none" && project.budget_is_monthly && `, ${t("projects.overview.resetsMonthly")}`}
              </dd>
            </>
          )}
          {budgets && project.budget_by !== "none" && (
            <>
              <dt>{t("projects.overview.budgetAlerts")}</dt>
              <dd>
                {project.notify_when_over_budget
                  ? project.budget_alert_percent != null
                    ? t("projects.overview.alertsAt", { percent: formatPercent(Number(project.budget_alert_percent) / 100) })
                    : t("projects.overview.alertsOver")
                  : t("projects.overview.alertsOff")}
              </dd>
              <dt>{t("projects.overview.budgetVisible")}</dt>
              <dd>{project.show_budget_to_all ? t("projects.overview.visibleAll") : t("projects.overview.visibleManagers")}</dd>
            </>
          )}
          {dates && (
            <>
              <dt>{t("projects.overview.dates")}</dt>
              <dd>{dates}</dd>
            </>
          )}
          <dt>{t("projects.overview.assigned")}</dt>
          <dd>
            {t("projects.overview.taskCount", { count: project.tasks.filter((x) => x.is_active).length })}
            {team && <>, {t("projects.overview.peopleCount", { count: project.members.filter((m) => m.is_active).length })}</>}
          </dd>
          {project.notes && (
            <>
              <dt>{t("projects.fields.notes")}</dt>
              <dd style={{ whiteSpace: "pre-wrap", maxWidth: "70ch" }}>{project.notes}</dd>
            </>
          )}
        </dl>
      </section>
    </div>
  );
}

function BudgetSummary({
  project,
  usage,
  durationStyle,
  onEditSettings,
  budgets,
}: {
  project: Project;
  usage: Budget;
  durationStyle: DurationStyle;
  onEditSettings: () => void;
  budgets: boolean;
}) {
  const { t } = useTranslation();
  const tracked = formatDuration(usage.spent_seconds, durationStyle);

  if (usage.budget_by === "none" || !budgets) {
    return (
      <div className="stack" style={{ gap: 12 }}>
        <dl className="budget-figures">
          <div>
            <dt>{t("projects.overview.tracked")}</dt>
            <dd>{tracked}</dd>
          </div>
        </dl>
        {budgets && (
          <p className="muted">
            {t("projects.overview.noBudget")}{" "}
            <button type="button" className="link-button" style={{ color: "var(--action)" }} onClick={onEditSettings}>
              {t("projects.overview.setBudget")}
            </button>
          </p>
        )}
      </div>
    );
  }

  const f = budgetFigures(usage, durationStyle);
  if (!f.canShow) return <p className="notice">{t("projects.overview.moneyHidden")}</p>;

  return (
    <div className="stack" style={{ gap: 12 }}>
      <dl className="budget-figures">
        <div>
          <dt>{t("projects.overview.spent")}</dt>
          <dd>{f.spent}</dd>
        </div>
        <div>
          <dt>{t("projects.overview.budgetTotal")}</dt>
          <dd>{f.hasBudget ? f.budget : <span className="muted">{t("projects.overview.notSet")}</span>}</dd>
        </div>
        {f.hasBudget && (
          <div>
            <dt>{f.over ? t("projects.overview.overBy") : t("projects.overview.remaining")}</dt>
            <dd className={f.over ? "over" : undefined}>{f.remaining}</dd>
          </div>
        )}
        {f.money && (
          <div>
            <dt>{t("projects.overview.tracked")}</dt>
            <dd>{tracked}</dd>
          </div>
        )}
      </dl>
      {f.hasBudget && (
        <div className="budget-meter">
          <BudgetMeter usage={usage} alertPercent={project.budget_alert_percent} compact />
          <div className="meter-caption">
            <span>
              {usage.percent_used != null
                ? t("projects.budget.percentUsed", { percent: formatPercent(Number(usage.percent_used) / 100) })
                : ""}
            </span>
            {project.budget_alert_percent != null && (
              <span>{t("projects.overview.warnAt", { percent: formatPercent(Number(project.budget_alert_percent) / 100) })}</span>
            )}
          </div>
        </div>
      )}
      {!f.hasBudget && isLineBudget(usage.budget_by) && <p className="muted">{t(`projects.form.lineBudget.${usage.budget_by}`)}</p>}
      {isLineBudget(usage.budget_by) && <BudgetLines project={project} usage={usage} durationStyle={durationStyle} />}
    </div>
  );
}

function BudgetLines({ project, usage, durationStyle }: { project: Project; usage: Budget; durationStyle: DurationStyle }) {
  const { t } = useTranslation();
  const money = usage.measure === "money";
  const fmt = (v: number | null | undefined) =>
    v === null || v === undefined ? "" : money ? formatMoney(v, usage.currency) : formatDuration(v, durationStyle);
  const lines = usage.lines;
  const f = budgetFigures(usage, durationStyle);
  return (
    <table className="ledger" style={{ marginTop: 8 }}>
      <thead>
        <tr>
          <th>{usage.budget_by === "person" ? t("projects.overview.person") : t("projects.overview.task")}</th>
          <th className="num">{t("projects.overview.budgetTotal")}</th>
          <th className="num">{t("projects.overview.spent")}</th>
          <th className="num">{t("projects.overview.remaining")}</th>
          <th style={{ width: "22%" }}>
            <span className="sr-only">{t("projects.overview.usage")}</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {lines.length === 0 && (
          <tr>
            <td colSpan={5} className="muted">
              {usage.budget_by === "person" ? t("projects.overview.noPeopleLines") : t("projects.overview.noTaskLines")}
            </td>
          </tr>
        )}
        {lines.map((l) => {
          const budget = money ? l.budget_amount : l.budget_seconds;
          const spent = (money ? l.spent_amount : l.spent_seconds) as number | undefined;
          const remaining = budget != null && spent !== undefined ? budget - spent : null;
          return (
            <tr key={l.id}>
              <td>{l.name}</td>
              <td className="num">{budget != null ? fmt(budget) : <span className="muted">{t("projects.overview.notSet")}</span>}</td>
              <td className="num">{fmt(spent)}</td>
              <td className={["num", remaining !== null && remaining < 0 ? "over-text" : ""].join(" ")}>
                {remaining === null ? "" : remaining < 0 ? t("projects.overview.overAmount", { amount: fmt(-remaining) }) : fmt(remaining)}
              </td>
              <td>{spent !== undefined && <Meter spent={spent} budget={budget} alertPercent={project.budget_alert_percent} label={l.name} />}</td>
            </tr>
          );
        })}
      </tbody>
      {lines.length > 0 && (
        <tfoot>
          <tr>
            <td className="total">{t("projects.overview.total")}</td>
            <td className="total num">{f.budget}</td>
            <td className="total num">{f.spent}</td>
            <td className={["total num", f.over ? "over-text" : ""].join(" ")}>
              {f.hasBudget ? (f.over ? t("projects.overview.overAmount", { amount: f.remaining }) : f.remaining) : ""}
            </td>
            <td className="total" />
          </tr>
        </tfoot>
      )}
    </table>
  );
}
