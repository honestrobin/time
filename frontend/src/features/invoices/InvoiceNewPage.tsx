// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { useMemo, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, PageHeader, SelectField, TextField } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { formatDuration, formatMoney } from "../../lib/format";
import { byName, clientsQuery, useAccountDefaults } from "../projects/queries";
import { invoiceKeys } from "./queries";
import "./invoices.css";

type Source = "time" | "blank";
type Period = "all" | "lastMonth" | "thisMonth" | "custom";

function periodRange(period: Period, from: string, to: string): { from?: string; to?: string } {
  const now = new Date();
  const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  switch (period) {
    case "lastMonth":
      return { from: iso(new Date(now.getFullYear(), now.getMonth() - 1, 1)), to: iso(new Date(now.getFullYear(), now.getMonth(), 0)) };
    case "thisMonth":
      return { from: iso(new Date(now.getFullYear(), now.getMonth(), 1)), to: iso(new Date(now.getFullYear(), now.getMonth() + 1, 0)) };
    case "custom":
      return { from: from || undefined, to: to || undefined };
    default:
      return {};
  }
}

/** Start an invoice: pick the client, then bill their uninvoiced time and expenses or start blank. */
export function InvoiceNewPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { durationStyle } = useAccountDefaults();
  const clients = useQuery(clientsQuery);
  const active = useMemo(() => [...(clients.data?.data ?? [])].filter((c) => c.is_active).sort(byName), [clients.data]);
  // The uninvoiced report links here with the client and its period.
  const prefill = useSearch({ from: "/app/invoices/new" });
  const [clientId, setClientId] = useState(prefill.client ?? "");
  const [source, setSource] = useState<Source>("time");
  const [period, setPeriod] = useState<Period>(prefill.from || prefill.to ? "custom" : "all");
  const [from, setFrom] = useState(prefill.from ?? "");
  const [to, setTo] = useState(prefill.to ?? "");
  const [grouping, setGrouping] = useState("project");
  const [includeExpenses, setIncludeExpenses] = useState(true);
  const range = periodRange(period, from, to);

  const preview = useQuery({
    queryKey: ["invoices", "uninvoiced", clientId, range.from, range.to],
    queryFn: () => unwrap(api.GET("/api/v1/invoices/uninvoiced", { params: { query: { client_id: clientId, from: range.from, to: range.to } } })),
    enabled: !!clientId && source === "time",
  });
  // Billable time priced at 0 never reaches an invoice. Say where it is, instead of "nothing to bill".
  const open = useQuery({
    queryKey: ["invoices", "unpriced", clientId, range.from, range.to],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/time_entries", {
          params: { query: { client_id: clientId, from: range.from, to: range.to, billable: true, invoiced: false, is_running: false, limit: 500 } },
        }),
      ),
    enabled: !!clientId && source === "time",
  });
  const unpriced = useMemo(() => {
    const rows = (open.data?.data ?? []).filter((e) => e.billable_rate === 0);
    const projects = new Map<string, string>();
    for (const e of rows) projects.set(e.project.id, e.project.name);
    return { seconds: rows.reduce((sum, e) => sum + e.rounded_seconds, 0), projects: [...projects] };
  }, [open.data]);
  const create = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/invoices", {
          body: {
            client_id: clientId,
            ...(source === "time" ? { from_time: { from: range.from, to: range.to, grouping, include_expenses: includeExpenses } } : {}),
          } as never,
        }),
      ),
    onSuccess: async (inv) => {
      await qc.invalidateQueries({ queryKey: invoiceKeys.all });
      await navigate({ to: "/invoices/$invoiceId", params: { invoiceId: inv.id } });
    },
  });
  const err = create.error ? errorInfo(create.error) : null;
  const p = preview.data;
  const nothing = source === "time" && p && p.amount === 0 && (p.expense_amount === 0 || !includeExpenses);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate();
  };

  return (
    <div className="page page-narrow">
      <PageHeader title={t("invoices.newTitle")} lead={t("invoices.newLead")} />
      <form className="stack" onSubmit={submit}>
        <SelectField
          label={t("invoices.fields.client")}
          value={clientId}
          onChange={setClientId}
          placeholder={active.length ? t("invoices.chooseClient") : t("invoices.noClients")}
          options={active.map((c) => ({ value: c.id, label: `${c.name} (${c.currency})` }))}
          error={err?.fields.client_id}
        />

        <fieldset className="choice">
          <legend>{t("invoices.source.legend")}</legend>
          <label className="choice-option">
            <input type="radio" name="source" checked={source === "time"} onChange={() => setSource("time")} />
            <span>
              <strong>{t("invoices.source.time")}</strong>
              <span className="muted">{t("invoices.source.timeHint")}</span>
            </span>
          </label>
          <label className="choice-option">
            <input type="radio" name="source" checked={source === "blank"} onChange={() => setSource("blank")} />
            <span>
              <strong>{t("invoices.source.blank")}</strong>
              <span className="muted">{t("invoices.source.blankHint")}</span>
            </span>
          </label>
        </fieldset>

        {source === "time" && (
          <>
            <div className="form-grid">
              <SelectField
                label={t("invoices.period.label")}
                value={period}
                onChange={(v) => setPeriod(v as Period)}
                options={(["all", "lastMonth", "thisMonth", "custom"] as const).map((v) => ({ value: v, label: t(`invoices.period.${v}`) }))}
              />
              <SelectField
                label={t("invoices.grouping.label")}
                hint={t(`invoices.grouping.${grouping}Hint`)}
                value={grouping}
                onChange={setGrouping}
                options={(["project", "task", "person", "detailed"] as const).map((v) => ({ value: v, label: t(`invoices.grouping.${v}`) }))}
              />
              {period === "custom" && (
                <>
                  <TextField type="date" label={t("invoices.period.from")} value={from} onChange={setFrom} />
                  <TextField type="date" label={t("invoices.period.to")} value={to} onChange={setTo} />
                </>
              )}
            </div>
            <Checkbox checked={includeExpenses} onChange={setIncludeExpenses} label={t("invoices.includeExpenses")} />

            {clientId && (
              <div className="uninvoiced-preview">
                {preview.isLoading ? (
                  <p className="muted">{t("app.loading")}</p>
                ) : p && p.projects.length > 0 ? (
                  <table className="ledger">
                    <thead>
                      <tr>
                        <th>{t("invoices.col.project")}</th>
                        <th className="num">{t("invoices.col.hours")}</th>
                        <th className="num">{t("invoices.col.time")}</th>
                        {includeExpenses && <th className="num">{t("invoices.col.expenses")}</th>}
                      </tr>
                    </thead>
                    <tbody>
                      {p.projects.map((row) => (
                        <tr key={row.project.id}>
                          <td>{row.project.name}</td>
                          <td className="num">{formatDuration(row.seconds, durationStyle)}</td>
                          <td className="num">{formatMoney(row.amount, p.currency)}</td>
                          {includeExpenses && <td className="num">{row.expenses ? formatMoney(row.expense_amount, p.currency) : "—"}</td>}
                        </tr>
                      ))}
                    </tbody>
                    <tfoot>
                      <tr>
                        <td className="total">{t("invoices.total")}</td>
                        <td />
                        <td className="num total">{formatMoney(p.amount, p.currency)}</td>
                        {includeExpenses && <td className="num total">{formatMoney(p.expense_amount, p.currency)}</td>}
                      </tr>
                    </tfoot>
                  </table>
                ) : unpriced.seconds > 0 ? null : (
                  <p className="notice">{t("invoices.nothingToBill")}</p>
                )}
                {unpriced.seconds > 0 && (
                  <p className="notice">
                    {t("invoices.unpriced", { count: unpriced.projects.length, duration: formatDuration(unpriced.seconds, durationStyle) })}{" "}
                    {unpriced.projects.map(([id, name], i) => (
                      <span key={id}>
                        {i > 0 && ", "}
                        <Link to="/projects/$projectId" params={{ projectId: id }}>
                          {name}
                        </Link>
                      </span>
                    ))}
                    . {t("invoices.unpricedHow")}
                  </p>
                )}
              </div>
            )}
          </>
        )}

        {err && (err.fields.from_time || !Object.keys(err.fields).length) && <p className="notice notice-error">{err.fields.from_time ?? err.message}</p>}
        <div className="form-actions">
          <Button type="submit" variant="primary" busy={create.isPending} disabled={!clientId || !!nothing}>
            {t("invoices.create")}
          </Button>
          <Button onClick={() => navigate({ to: "/invoices" })}>{t("app.cancel")}</Button>
        </div>
      </form>
    </div>
  );
}
