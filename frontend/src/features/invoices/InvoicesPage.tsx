// SPDX-License-Identifier: AGPL-3.0-only
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, EmptyState, LoadingRow, PageHeader } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { formatDate, formatMoney } from "../../lib/format";
import { useHotkeys } from "../../lib/hotkeys";
import { displayState, invoiceKeys, stateBadge } from "./queries";
import "./invoices.css";

const FILTERS = ["outstanding", "overdue", "draft", "paid", "all"] as const;
type Filter = (typeof FILTERS)[number];

/** Who owes what: outstanding and overdue amounts per currency, then the invoices themselves. */
export function InvoicesPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [filter, setFilter] = useState<Filter>("outstanding");
  const summary = useQuery({ queryKey: invoiceKeys.summary, queryFn: () => unwrap(api.GET("/api/v1/invoices/summary")) });
  const list = useInfiniteQuery({
    queryKey: invoiceKeys.list(filter),
    queryFn: ({ pageParam }) => unwrap(api.GET("/api/v1/invoices", { params: { query: { state: filter, cursor: pageParam, limit: 50 } } })),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor ?? undefined,
  });
  const rows = list.data?.pages.flatMap((p) => p.data) ?? [];
  useHotkeys({ n: () => void navigate({ to: "/invoices/new" }) });

  return (
    <div className="page">
      <PageHeader
        title={t("invoices.title")}
        lead={t("invoices.lead")}
        actions={
          <Link to="/invoices/new" className="btn btn-primary">
            {t("invoices.new")}
          </Link>
        }
      />

      {summary.data && summary.data.totals.length > 0 && (
        <dl className="invoice-summary">
          {summary.data.totals.map((c) => (
            <div key={c.currency}>
              <dt>{t("invoices.summary.outstanding", { count: c.outstanding_count })}</dt>
              <dd>{formatMoney(c.outstanding, c.currency)}</dd>
              {c.overdue > 0 && (
                <dd className="overdue">
                  {t("invoices.summary.overdue", { amount: formatMoney(c.overdue, c.currency), count: c.overdue_count })}
                </dd>
              )}
            </div>
          ))}
        </dl>
      )}

      <div className="seg" role="group" aria-label={t("invoices.filter")} style={{ marginBottom: 16 }}>
        {FILTERS.map((f) => (
          <button key={f} type="button" aria-pressed={filter === f} onClick={() => setFilter(f)}>
            {t(`invoices.filters.${f}`)}
            {f === "draft" && summary.data && summary.data.draft_count > 0 ? ` (${summary.data.draft_count})` : ""}
          </button>
        ))}
      </div>

      {list.error ? (
        <p className="notice notice-error">{errorInfo(list.error).message}</p>
      ) : !list.isLoading && rows.length === 0 ? (
        <EmptyState
          title={t(`invoices.empty.${filter}`)}
          body={filter === "outstanding" || filter === "all" ? t("invoices.empty.body") : undefined}
          action={
            (filter === "outstanding" || filter === "all") && (
              <Link to="/invoices/new" className="btn btn-primary">
                {t("invoices.new")}
              </Link>
            )
          }
        />
      ) : (
        <table className="ledger invoice-table">
          <thead>
            <tr>
              <th className="col-narrow">{t("invoices.col.number")}</th>
              <th>{t("invoices.col.client")}</th>
              <th className="col-narrow">{t("invoices.col.issued")}</th>
              <th className="col-narrow">{t("invoices.col.due")}</th>
              <th className="num">{t("invoices.col.total")}</th>
              <th className="num">{t("invoices.col.dueAmount")}</th>
              <th className="col-narrow">{t("invoices.col.status")}</th>
            </tr>
          </thead>
          <tbody>
            {list.isLoading && <LoadingRow colSpan={7} />}
            {rows.map((i) => {
              const state = displayState(i);
              return (
                <tr key={i.id}>
                  <td className="nowrap">
                    <Link to="/invoices/$invoiceId" params={{ invoiceId: i.id }} className="row-link">
                      {i.number ?? t("invoices.draft")}
                    </Link>
                  </td>
                  <td>
                    {i.client.name}
                    {i.subject && <div className="muted small">{i.subject}</div>}
                  </td>
                  <td>{formatDate(i.issue_date, "short")}</td>
                  <td>{i.due_date ? formatDate(i.due_date, "short") : "—"}</td>
                  <td className="num">{formatMoney(i.total, i.currency)}</td>
                  <td className="num">{i.state === "draft" || i.state === "void" ? "—" : formatMoney(i.due, i.currency)}</td>
                  <td>
                    <span className={stateBadge(state)}>{t(`invoices.state.${state}`)}</span>
                    {i.source === "harvest_import" && <span className="muted small"> {t("invoices.imported")}</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {list.hasNextPage && (
        <Button onClick={() => list.fetchNextPage()} busy={list.isFetchingNextPage} style={{ marginTop: 16 }}>
          {t("app.loadMore")}
        </Button>
      )}
    </div>
  );
}
