// SPDX-License-Identifier: AGPL-3.0-only
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { EmptyState, LoadingRow } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { featureOn, useFeature, useFeatures } from "../../lib/features";
import { formatDuration, formatMoney, formatPercent, type DurationStyle } from "../../lib/format";
import { filterQuery, TIME_GROUPS, type ReportSearch, type TimeGroup } from "./search";
import type { Range } from "./period";

type TimeReportData = Schemas["TimeReport"];
type Row = Schemas["TimeReportRow"];

export const reportKeys = { all: ["reports"] as const };

export function useTimeReport(search: ReportSearch, range: Range, group: TimeGroup) {
  const query = { ...filterQuery(search, range), group_by: group };
  return useQuery({
    queryKey: ["reports", "time", query],
    queryFn: () => unwrap(api.GET("/api/v1/reports/time", { params: { query } })),
    placeholderData: (prev) => prev,
  });
}

/** One line per currency: amounts in different currencies are never added together. */
export function Amounts({ amounts, field }: { amounts: { currency: string; [k: string]: unknown }[]; field: string }) {
  const shown = amounts.filter((a) => typeof a[field] === "number");
  if (shown.length === 0) return <span className="muted">—</span>;
  return (
    <>
      {shown.map((a) => (
        <div key={a.currency}>{formatMoney(a[field] as number, a.currency)}</div>
      ))}
    </>
  );
}

/**
 * Where clicking a row leads: client → its projects → their tasks → people → their entries. A
 * grouping whose feature is switched off is skipped (tasks without the task list, people
 * without the team).
 */
function drill(group: TimeGroup, row: Row, groups: readonly TimeGroup[]): Partial<ReportSearch> {
  const filter = { client: { client: [row.id] }, project: { project: [row.id] }, task: { task: [row.id] }, person: { person: [row.id] } }[group];
  const next = groups[groups.indexOf(group) + 1];
  return next ? { group: next, ...filter } : { tab: "detailed", ...filter, group: undefined };
}

function drillLabel(group: TimeGroup, groups: readonly TimeGroup[]): string {
  const next = groups[groups.indexOf(group) + 1];
  if (group === "project" && next !== "task") return next === "person" ? "reports.drill.projectPeople" : "reports.drill.projectEntries";
  if (group === "task" && next !== "person") return "reports.drill.taskEntries";
  return `reports.drill.${group}`;
}

/** The groupings on offer: tasks only with the task list, people only with the team. */
function useTimeGroups(): TimeGroup[] {
  const features = useFeatures();
  return TIME_GROUPS.filter((g) => (g !== "task" || featureOn(features, "tasks")) && (g !== "person" || featureOn(features, "team")));
}

export function Summary({ data, durationStyle, canSeeRates }: { data: TimeReportData; durationStyle: DurationStyle; canSeeRates: boolean }) {
  const { t } = useTranslation();
  const invoices = useFeature("invoices");
  const share = data.seconds > 0 ? data.billable_seconds / data.seconds : 0;
  return (
    <dl className="report-summary">
      <div>
        <dt>{t("reports.summary.hours")}</dt>
        <dd>{formatDuration(data.seconds, durationStyle)}</dd>
      </div>
      <div>
        <dt>{t("reports.summary.billableHours")}</dt>
        <dd>
          {formatDuration(data.billable_seconds, durationStyle)} <span className="report-share">{formatPercent(share)}</span>
        </dd>
      </div>
      {canSeeRates && (
        <>
          <div>
            <dt>{t("reports.summary.billableAmount")}</dt>
            <dd className="report-money">
              <Amounts amounts={data.amounts} field="billable_amount" />
            </dd>
          </div>
          {invoices && (
            <div>
              <dt>{t("reports.summary.uninvoiced")}</dt>
              <dd className="report-money">
                <Amounts amounts={data.amounts} field="uninvoiced_amount" />
              </dd>
            </div>
          )}
        </>
      )}
    </dl>
  );
}

interface Props {
  search: ReportSearch;
  range: Range;
  durationStyle: DurationStyle;
  canSeeRates: boolean;
  onSearch: (patch: Partial<ReportSearch>) => void;
}

/** Hours and amounts by client, project, task or person, with billable and non-billable bars. */
export function TimeReport({ search, range, durationStyle, canSeeRates, onSearch }: Props) {
  const { t } = useTranslation();
  const groups = useTimeGroups();
  const invoices = useFeature("invoices");
  const uninvoiced = canSeeRates && invoices;
  const group: TimeGroup = (groups as readonly string[]).includes(search.group ?? "") ? (search.group as TimeGroup) : "project";
  const report = useTimeReport(search, range, group);
  const data = report.data;
  const longest = Math.max(1, ...(data?.rows ?? []).map((r) => r.seconds));
  const cols = 4 + (canSeeRates ? 1 : 0) + (uninvoiced ? 1 : 0);

  return (
    <section aria-label={t("reports.tabs.time")}>
      {data && <Summary data={data} durationStyle={durationStyle} canSeeRates={canSeeRates} />}
      <div className="report-controls">
        <span className="muted small">{t("reports.groupBy")}</span>
        <div className="seg seg-sm" role="group" aria-label={t("reports.groupBy")}>
          {groups.map((g) => (
            <button key={g} type="button" aria-pressed={group === g} onClick={() => onSearch({ group: g })}>
              {t(`reports.groups.${g}`)}
            </button>
          ))}
        </div>
        {data && data.rounding_minutes > 0 && (
          <span className="muted small">{t(data.rounding_mode === "nearest" ? "reports.roundedNearest" : "reports.rounded", { minutes: data.rounding_minutes })}</span>
        )}
      </div>

      {report.error ? (
        <p className="notice notice-error">{errorInfo(report.error).message}</p>
      ) : data && data.rows.length === 0 ? (
        <EmptyState title={t("reports.empty.title")} body={t("reports.empty.body")} />
      ) : (
        <div className="table-scroll">
          <table className="ledger report-table">
            <thead>
              <tr>
                <th>{t(`reports.groups.${group}`)}</th>
                <th className="num">{t("reports.col.hours")}</th>
                <th className="col-bar" aria-hidden />
                <th className="num">{t("reports.col.billableHours")}</th>
                {canSeeRates && <th className="num">{t("reports.col.billableAmount")}</th>}
                {uninvoiced && <th className="num">{t("reports.col.uninvoiced")}</th>}
              </tr>
            </thead>
            <tbody>
              {report.isLoading && <LoadingRow colSpan={cols} />}
              {data?.rows.map((r) => {
                const share = r.seconds > 0 ? r.billable_seconds / r.seconds : 0;
                return (
                  <tr key={r.id}>
                    <td>
                      <button type="button" className="link-button" onClick={() => onSearch(drill(group, r, groups))} title={t(drillLabel(group, groups))}>
                        {r.name}
                      </button>
                      {r.client_name && <div className="muted small">{r.client_name}</div>}
                    </td>
                    <td className="num">{formatDuration(r.seconds, durationStyle)}</td>
                    <td className="col-bar">
                      <div className="hours-bar" style={{ width: `${(r.seconds / longest) * 100}%` }} title={t("reports.billableShare", { percent: formatPercent(share) })}>
                        <span style={{ width: `${share * 100}%` }} />
                      </div>
                    </td>
                    <td className="num">
                      {formatDuration(r.billable_seconds, durationStyle)}
                      <div className="muted small">{formatPercent(share)}</div>
                    </td>
                    {canSeeRates && (
                      <td className="num">
                        <Amounts amounts={r.amounts} field="billable_amount" />
                      </td>
                    )}
                    {uninvoiced && (
                      <td className="num">
                        <Amounts amounts={r.amounts} field="uninvoiced_amount" />
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
            {data && data.rows.length > 1 && (
              <tfoot>
                <tr>
                  <td className="total">{t("reports.total")}</td>
                  <td className="num total">{formatDuration(data.seconds, durationStyle)}</td>
                  <td className="total" />
                  <td className="num total">{formatDuration(data.billable_seconds, durationStyle)}</td>
                  {canSeeRates && (
                    <td className="num total">
                      <Amounts amounts={data.amounts} field="billable_amount" />
                    </td>
                  )}
                  {uninvoiced && (
                    <td className="num total">
                      <Amounts amounts={data.amounts} field="uninvoiced_amount" />
                    </td>
                  )}
                </tr>
              </tfoot>
            )}
          </table>
        </div>
      )}
    </section>
  );
}
