// SPDX-License-Identifier: AGPL-3.0-only
// QuickBooks Online and Xero: connect, choose how invoices are booked, see what failed (spec §8).
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, PageHeader, SelectField, useToast } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { formatDateTime } from "../../lib/format";
import { usePermissions } from "../../lib/session";

type Connection = Schemas["AccountingConnectionView"];
type Mapping = Schemas["Mapping"];

const connectionsQuery = { queryKey: ["accounting"], queryFn: () => unwrap(api.GET("/api/v1/accounting")) };

export function AccountingPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const list = useQuery(connectionsQuery);

  // Back from the provider's authorisation page.
  useEffect(() => {
    const q = new URLSearchParams(location.search);
    const result = q.get("accounting");
    if (!result) return;
    toast(result === "connected" ? t("accounting.connected") : t("accounting.connectFailed"), result === "connected" ? "ok" : "error");
    history.replaceState(null, "", location.pathname);
  }, [t, toast]);

  return (
    <div className="page page-narrow">
      <PageHeader title={t("accounting.title")} lead={t("accounting.lead")} />
      {list.error && <p className="notice notice-error">{errorInfo(list.error).message}</p>}
      {list.data?.map((c) => <ConnectionSection key={c.kind} c={c} />)}
    </div>
  );
}

function ConnectionSection({ c }: { c: Connection }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const perms = usePermissions();
  const connect = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/accounting/{kind}/connect", { params: { path: { kind: c.kind } } })),
    onSuccess: (r) => location.assign((r as { url: string }).url),
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const disconnect = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/accounting/{kind}", { params: { path: { kind: c.kind } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: connectionsQuery.queryKey }),
  });
  return (
    <section className="form-section stack">
      <h2>{c.label}</h2>
      {!c.available ? (
        <p className="muted">{t("accounting.unavailable", { name: c.label })}</p>
      ) : !c.connected ? (
        <>
          <p className="muted">{t("accounting.notConnected", { name: c.label })}</p>
          {perms.isAdmin && (
            <div>
              <Button variant="primary" onClick={() => connect.mutate()} busy={connect.isPending}>
                {t("accounting.connect", { name: c.label })}
              </Button>
            </div>
          )}
        </>
      ) : (
        <>
          <p className="notice notice-ok">{t("accounting.connectedTo", { org: c.organisation ?? c.label })}</p>
          <MappingForm c={c} />
          <Queue c={c} />
          {perms.isAdmin && (
            <div>
              <Button variant="danger" onClick={() => disconnect.mutate()} busy={disconnect.isPending}>
                {t("accounting.disconnect")}
              </Button>
            </div>
          )}
        </>
      )}
    </section>
  );
}

function MappingForm({ c }: { c: Connection }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const perms = usePermissions();
  const options = useQuery({
    queryKey: ["accounting", c.kind, "options"],
    queryFn: () => unwrap(api.GET("/api/v1/accounting/{kind}/options", { params: { path: { kind: c.kind } } })),
  });
  const [m, setM] = useState<Mapping>(() => c.mapping ?? { tax_codes: {}, auto_push: true });
  const save = useMutation({
    mutationFn: () => unwrap(api.PATCH("/api/v1/accounting/{kind}/mapping", { params: { path: { kind: c.kind } }, body: m })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["accounting"] });
      toast(t("app.saved"));
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  if (options.error) return <p className="notice notice-error">{errorInfo(options.error).message}</p>;
  const o = options.data;
  if (!o) return null;
  const readOnly = !perms.isAdmin;
  const choices = (list: { id: string; name: string }[]) => list.map((x) => ({ value: x.id, label: x.name }));
  return (
    <div className="stack">
      <h3>{t("accounting.booking")}</h3>
      {o.items.length > 0 && (
        <SelectField label={t("accounting.item")} hint={t("accounting.itemHint")} value={m.item_id ?? ""} onChange={(v) => setM({ ...m, item_id: v })} options={choices(o.items)} placeholder={t("accounting.choose")} disabled={readOnly} />
      )}
      {o.sales_accounts.length > 0 && (
        <SelectField label={t("accounting.salesAccount")} value={m.sales_account ?? ""} onChange={(v) => setM({ ...m, sales_account: v })} options={choices(o.sales_accounts)} placeholder={t("accounting.choose")} disabled={readOnly} />
      )}
      <SelectField label={t("accounting.paymentAccount")} hint={t("accounting.paymentAccountHint")} value={m.payment_account ?? ""} onChange={(v) => setM({ ...m, payment_account: v })} options={choices(o.payment_accounts)} placeholder={t("accounting.choose")} disabled={readOnly} />
      <h3>{t("accounting.taxes")}</h3>
      <p className="muted small">{t("accounting.taxesHint", { name: c.label })}</p>
      <div className="form-grid">
        {o.our_taxes.map((tax) => (
          <SelectField
            key={tax.id}
            label={tax.name}
            value={m.tax_codes?.[tax.id] ?? ""}
            onChange={(v) => setM({ ...m, tax_codes: { ...(m.tax_codes ?? {}), [tax.id]: v } })}
            options={choices(o.tax_codes)}
            placeholder={t("accounting.notMapped")}
            disabled={readOnly}
          />
        ))}
      </div>
      <Checkbox checked={m.auto_push ?? true} onChange={(v) => setM({ ...m, auto_push: v })} label={t("accounting.autoPush")} hint={t("accounting.autoPushHint", { name: c.label })} disabled={readOnly} />
      {!readOnly && (
        <div>
          <Button variant="primary" onClick={() => save.mutate()} busy={save.isPending}>
            {t("app.save")}
          </Button>
        </div>
      )}
    </div>
  );
}

function Queue({ c }: { c: Connection }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const failed = useQuery({
    queryKey: ["accounting", c.kind, "queue", "failed"],
    queryFn: () => unwrap(api.GET("/api/v1/accounting/{kind}/queue", { params: { path: { kind: c.kind }, query: { status: "failed" } } })),
  });
  const retry = useMutation({
    mutationFn: (id: string) => unwrap(api.POST("/api/v1/accounting/items/{id}/retry", { params: { path: { id } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["accounting"] }),
  });
  return (
    <div className="stack">
      <h3>{t("accounting.queueTitle")}</h3>
      <p className="muted small">{t("accounting.queueStatus", { pending: c.pending, failed: c.failed })}</p>
      {failed.data && failed.data.length > 0 && (
        <table className="ledger">
          <tbody>
            {failed.data.map((x) => (
              <tr key={x.id}>
                <td className="nowrap">
                  {t(`accounting.entity.${x.entity_type}`, { number: x.invoice_number ?? "—" })}
                  <div className="muted small">{formatDateTime(x.updated_at)}</div>
                </td>
                <td className="small">{x.last_error}</td>
                <td className="num">
                  <Button size="sm" onClick={() => retry.mutate(x.id)} busy={retry.isPending && retry.variables === x.id}>
                    {t("accounting.retry")}
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
