// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, TextField, useToast } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";

export const peppolQuery = { queryKey: ["einvoicing", "peppol"], queryFn: () => unwrap(api.GET("/api/v1/einvoicing/peppol")) };

/** Connecting Peppol sending: the account's own Storecove contract, or Honest Robin's where offered. */
export function PeppolSettings({ readOnly }: { readOnly: boolean }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const status = useQuery(peppolQuery);
  const [key, setKey] = useState("");
  // What disconnecting couldn't end at Storecove, and how to end it there.
  const [leftover, setLeftover] = useState<string | null>(null);
  const connect = useMutation({
    mutationFn: (apiKey: string | null) => unwrap(api.POST("/api/v1/einvoicing/peppol", { body: apiKey ? { api_key: apiKey } : {} })),
    onSuccess: (s) => {
      qc.setQueryData(peppolQuery.queryKey, s);
      setKey("");
      toast(t("peppol.connected"));
    },
  });
  const disconnect = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/einvoicing/peppol")),
    onSuccess: (r) => {
      setLeftover(r.note ?? null);
      void qc.invalidateQueries({ queryKey: peppolQuery.queryKey });
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const s = status.data;
  if (!s) return null;
  const err = connect.error ? errorInfo(connect.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    connect.mutate(key.trim());
  };
  return (
    <section className="form-section stack" style={{ marginTop: 40 }}>
      <h2>{t("peppol.title")}</h2>
      <p className="muted">{t("peppol.lead")}</p>
      {leftover && <p className="notice notice-warn">{leftover}</p>}
      {s.connected ? (
        <>
          <p className="notice notice-ok">{t(s.mode === "connect" ? "peppol.viaPlatform" : "peppol.viaOwn")}</p>
          {s.mode === "api_key" && s.webhook_url && (
            <div className="stack">
              <p className="small">{t("peppol.webhookHint")}</p>
              <TextField label={t("peppol.webhookUrl")} value={s.webhook_url} onChange={() => {}} readOnly />
              <TextField label={t("peppol.webhookSecret")} hint={t("peppol.webhookSecretHint")} value={s.webhook_secret ?? ""} onChange={() => {}} readOnly />
            </div>
          )}
          {!readOnly && (
            <div>
              <Button variant="danger" onClick={() => disconnect.mutate()} busy={disconnect.isPending}>
                {t("peppol.disconnect")}
              </Button>
            </div>
          )}
        </>
      ) : readOnly ? (
        <p className="muted">{t("peppol.notConnected")}</p>
      ) : (
        <>
          {s.platform_available && (
            <div className="stack">
              <p>{t("peppol.platformOffer")}</p>
              <div>
                <Button variant="primary" onClick={() => connect.mutate(null)} busy={connect.isPending && !key}>
                  {t("peppol.usePlatform")}
                </Button>
              </div>
              <p className="muted small">{t("peppol.orOwn")}</p>
            </div>
          )}
          <form className="stack" onSubmit={submit}>
            <TextField label={t("peppol.apiKey")} hint={t("peppol.apiKeyHint")} value={key} onChange={setKey} autoComplete="off" error={err?.fields.api_key ?? err?.fields.account} />
            <div>
              <Button type="submit" disabled={!key.trim()} busy={connect.isPending && !!key}>
                {t("peppol.connect")}
              </Button>
            </div>
          </form>
        </>
      )}
    </section>
  );
}
