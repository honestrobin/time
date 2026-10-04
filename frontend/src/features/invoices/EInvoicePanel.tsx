// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { Button, useToast } from "../../design";
import { api, downloadFile, errorInfo, unwrap } from "../../lib/api";
import { formatDateTime } from "../../lib/format";
import { peppolQuery } from "./PeppolSettings";

/**
 * The invoice as an e-invoice: each format, whether it's ready, what it still needs (with a link
 * to where to fill it in), and a download.
 */
export function EInvoicePanel({ invoiceId, clientId }: { invoiceId: string; clientId: string }) {
  const { t } = useTranslation();
  const toast = useToast();
  const q = useQuery({
    queryKey: ["invoices", "detail", invoiceId, "einvoice"],
    queryFn: () => unwrap(api.GET("/api/v1/invoices/{id}/einvoice/readiness", { params: { path: { id: invoiceId } } })),
  });
  const qc = useQueryClient();
  const peppol = useQuery(peppolQuery);
  const transmissions = useQuery({
    queryKey: ["invoices", "detail", invoiceId, "transmissions"],
    queryFn: () => unwrap(api.GET("/api/v1/invoices/{id}/einvoice/transmissions", { params: { path: { id: invoiceId } } })),
    // Follow a sent document until the provider reports delivery.
    refetchInterval: (q) => (q.state.data?.some((x) => x.status === "queued" || x.status === "sent") ? 15_000 : false),
  });
  const send = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/invoices/{id}/einvoice/send", { params: { path: { id: invoiceId } } })),
    onSuccess: (x) => {
      void qc.invalidateQueries({ queryKey: ["invoices", "detail", invoiceId, "transmissions"] });
      toast(x.status === "failed" ? x.last_error ?? t("invoices.einvoice.status.failed") : t("invoices.einvoice.sent"), x.status === "failed" ? "error" : "ok");
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const download = (format: string) =>
    downloadFile(`/api/v1/invoices/${invoiceId}/einvoice?format=${format}`).catch((e) => toast(errorInfo(e).message, "error"));
  if (!q.data) return null;
  const key = (p: Problem) => `${p.where}:${p.field}`;
  // What every format needs is said once; each format then lists only its own extras.
  const shared = q.data[0].problems.filter((p) => q.data.every((r) => r.problems.some((x) => key(x) === key(p))));
  const sharedKeys = new Set(shared.map(key));
  return (
    <section className="stack einvoice-panel" style={{ marginTop: 32 }}>
      <h2>{t("invoices.einvoice.title")}</h2>
      <p className="muted">{t("invoices.einvoice.lead")}</p>
      {shared.length > 0 && (
        <div className="notice">
          <p style={{ margin: 0 }}>{t("invoices.einvoice.first")}</p>
          <ProblemList problems={shared} clientId={clientId} />
        </div>
      )}
      <div className="table-scroll">
        <table className="ledger">
          <tbody>
            {q.data.map((r) => {
              const own = r.problems.filter((p) => !sharedKeys.has(key(p)));
              return (
                <tr key={r.format}>
                  <td className="nowrap">
                    <strong>{t(`invoices.einvoice.formats.${r.format}`)}</strong>
                  </td>
                  <td>
                    {r.ready ? (
                      <span className="badge badge-ok">{t("invoices.einvoice.ready")}</span>
                    ) : own.length > 0 ? (
                      <ProblemList problems={own} clientId={clientId} />
                    ) : (
                      <span className="muted small">{t("invoices.einvoice.afterThat")}</span>
                    )}
                  </td>
                  <td className="num nowrap">
                    {r.format === "peppol" && peppol.data?.connected && (
                      <Button size="sm" variant="primary" disabled={!r.ready} busy={send.isPending} onClick={() => send.mutate()} style={{ marginRight: 8 }}>
                        {t("invoices.einvoice.send")}
                      </Button>
                    )}
                    <Button size="sm" disabled={!r.ready} onClick={() => void download(r.format)}>
                      {t("invoices.einvoice.download", { format: r.format === "facturx" ? "PDF" : "XML" })}
                    </Button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {peppol.data && !peppol.data.connected && <p className="muted small">{t("invoices.einvoice.connectFirst")}</p>}
      {transmissions.data && transmissions.data.length > 0 && (
        <>
          <h3>{t("invoices.einvoice.transmissions")}</h3>
          <ul className="einvoice-problems">
            {transmissions.data.map((x) => (
              <li key={x.id}>
                {formatDateTime(x.created_at)}:{" "}
                <span className={x.status === "delivered" ? "badge badge-ok" : x.status === "failed" || x.status === "rejected" ? "badge badge-signal" : "badge"}>
                  {t(`invoices.einvoice.status.${x.status}`)}
                </span>
                {x.last_error && <span className="muted small"> {x.last_error}</span>}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

type Problem = { where: string; field: string; message: string };

/** Each problem is a link to where it's fixed: account settings, invoice settings or the client. */
function ProblemList({ problems, clientId }: { problems: Problem[]; clientId: string }) {
  return (
    <ul className="einvoice-problems">
      {problems.map((p) => (
        <li key={`${p.where}-${p.field}`}>
          {p.where === "account" && p.field === "invoice_contact" ? (
            <Link to="/settings/invoices">{p.message}</Link>
          ) : p.where === "account" ? (
            <Link to="/settings/account">{p.message}</Link>
          ) : p.where === "client" ? (
            <Link to="/clients" search={{ edit: clientId } as never}>
              {p.message}
            </Link>
          ) : (
            p.message
          )}
        </li>
      ))}
    </ul>
  );
}
