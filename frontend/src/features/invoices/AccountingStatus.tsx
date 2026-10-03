// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { Button, useToast } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";

/** Where this invoice and its payments stand in QuickBooks or Xero, with a button to push now. */
export function AccountingStatus({ invoiceId }: { invoiceId: string }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const connections = useQuery({ queryKey: ["accounting"], queryFn: () => unwrap(api.GET("/api/v1/accounting")), retry: false });
  const items = useQuery({
    queryKey: ["invoices", "detail", invoiceId, "accounting"],
    queryFn: () => unwrap(api.GET("/api/v1/invoices/{id}/accounting", { params: { path: { id: invoiceId } } })),
    refetchInterval: (q) => (q.state.data?.some((x) => x.status === "pending") ? 10_000 : false),
  });
  const push = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/invoices/{id}/accounting/push", { params: { path: { id: invoiceId } } })),
    onSuccess: (x) => qc.setQueryData(["invoices", "detail", invoiceId, "accounting"], x),
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const connected = connections.data?.filter((c) => c.connected) ?? [];
  if (connected.length === 0) return null;
  const label = (kind: string) => connected.find((c) => c.kind === kind)?.label ?? kind;
  return (
    <section className="stack" style={{ marginTop: 32 }}>
      <h2>{t("accounting.invoiceTitle")}</h2>
      {items.data && items.data.length > 0 ? (
        <ul className="einvoice-problems">
          {items.data.map((x) => (
            <li key={x.id}>
              {t(`accounting.entity.${x.entity_type}`, { number: x.invoice_number ?? "" })} → {label(x.provider)}:{" "}
              <span className={x.status === "done" ? "badge badge-ok" : x.status === "failed" ? "badge badge-signal" : "badge"}>{t(`accounting.status.${x.status}`)}</span>
              {x.status === "failed" && x.last_error && (
                <>
                  {" "}
                  <span className="small">{x.last_error}</span> <Link to="/settings/accounting" className="small">{t("accounting.openSettings")}</Link>
                </>
              )}
            </li>
          ))}
        </ul>
      ) : (
        <p className="muted">{t("accounting.notPushed")}</p>
      )}
      <div>
        <Button size="sm" onClick={() => push.mutate()} busy={push.isPending}>
          {t("accounting.pushNow")}
        </Button>
      </div>
    </section>
  );
}
