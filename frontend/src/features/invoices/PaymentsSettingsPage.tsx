// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, ConfirmDialog, PageHeader, TextField, useToast } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { usePermissions } from "../../lib/session";
import "./invoices.css";

/** Let clients pay invoices online with Stripe; the money goes to the account's own Stripe. */
export function PaymentsSettingsPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const perms = usePermissions();
  const status = useQuery({ queryKey: ["payments", "stripe"], queryFn: () => unwrap(api.GET("/api/v1/payments/stripe")) });
  const [secretKey, setSecretKey] = useState("");
  const [webhookSecret, setWebhookSecret] = useState("");
  const [confirm, setConfirm] = useState(false);
  const result = new URLSearchParams(location.search).get("stripe");
  const refresh = () => qc.invalidateQueries({ queryKey: ["payments", "stripe"] });

  const withKey = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/payments/stripe/key", { body: { secret_key: secretKey, webhook_secret: webhookSecret } })),
    onSuccess: () => {
      setSecretKey("");
      setWebhookSecret("");
      void refresh();
      toast(t("payments.connected"));
    },
  });
  const connect = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/payments/stripe/connect")),
    onSuccess: (r) => {
      window.location.href = (r as { url: string }).url;
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const disconnect = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/payments/stripe")),
    onSuccess: () => {
      setConfirm(false);
      void refresh();
      toast(t("payments.disconnected"));
    },
  });

  const s = status.data;
  const err = withKey.error ? errorInfo(withKey.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    withKey.mutate();
  };
  if (!s) return <div className="page">{t("app.loading")}</div>;

  return (
    <div className="page page-narrow">
      <PageHeader title={t("payments.title")} lead={t("payments.lead")} />
      {result === "connected" && <p className="notice notice-ok">{t("payments.connectedNotice")}</p>}
      {result === "error" && <p className="notice notice-error">{t("payments.connectError")}</p>}
      {!perms.isAdmin && <p className="notice">{t("settings.adminOnly")}</p>}

      {s.connected ? (
        <section className="stack">
          <p className="notice notice-ok">
            {t("payments.status", { name: s.account_name ?? s.external_account_id })}
            {s.mode === "connect" ? ` ${t("payments.viaConnect")}` : ` ${t("payments.viaKey")}`}
          </p>
          {s.webhook_url && (
            <div className="field">
              <span className="field-label">{t("payments.webhookUrl")}</span>
              <code className="copyable">{s.webhook_url}</code>
              <span className="field-hint">{t("payments.webhookUrlHint")}</span>
            </div>
          )}
          {s.last_error && <p className="notice notice-warn">{t("payments.lastError", { error: s.last_error })}</p>}
          {perms.isAdmin && (
            <div>
              <Button variant="danger" onClick={() => setConfirm(true)}>
                {t("payments.disconnect")}
              </Button>
            </div>
          )}
        </section>
      ) : (
        perms.isAdmin && (
          <div className="stack">
            {s.connect_available && (
              <section className="stack">
                <h2>{t("payments.connectTitle")}</h2>
                <p className="muted">{t("payments.connectLead")}</p>
                <div>
                  <Button variant="primary" busy={connect.isPending} onClick={() => connect.mutate()}>
                    {t("payments.connectButton")}
                  </Button>
                </div>
              </section>
            )}
            <section className="stack">
              <h2>{s.connect_available ? t("payments.keyTitleAlt") : t("payments.keyTitle")}</h2>
              <ol className="import-steps">
                <li>{t("payments.keyStep1")}</li>
                <li>{t("payments.keyStep2", { url: `${location.origin}/webhooks/stripe/…` })}</li>
                <li>{t("payments.keyStep3")}</li>
              </ol>
              <form className="stack" onSubmit={submit}>
                <TextField label={t("payments.secretKey")} hint={t("payments.secretKeyHint")} value={secretKey} onChange={setSecretKey} error={err?.fields.secret_key} autoComplete="off" />
                <TextField label={t("payments.webhookSecret")} hint={t("payments.webhookSecretHint")} value={webhookSecret} onChange={setWebhookSecret} error={err?.fields.webhook_secret} autoComplete="off" />
                {err && !Object.keys(err.fields).length && <p className="notice notice-error">{err.message}</p>}
                <div>
                  <Button type="submit" variant={s.connect_available ? "secondary" : "primary"} busy={withKey.isPending}>
                    {t("payments.keyButton")}
                  </Button>
                </div>
              </form>
            </section>
          </div>
        )
      )}
      <p className="muted small" style={{ marginTop: 24 }}>
        {t("payments.noFee")}
      </p>
      <ConfirmDialog
        open={confirm}
        onOpenChange={setConfirm}
        title={t("payments.disconnectTitle")}
        body={<p>{t("payments.disconnectBody")}</p>}
        confirmLabel={t("payments.disconnect")}
        onConfirm={() => disconnect.mutate()}
        busy={disconnect.isPending}
      />
    </div>
  );
}
