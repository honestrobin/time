// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, Dialog, DialogActions, TextField, useToast } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { formatDateTime } from "../../lib/format";
import { useMe } from "../../lib/session";
import { TwoFactorSection } from "./TwoFactorSection";

export function ProfilePage() {
  const { t } = useTranslation();
  return (
    <div className="page page-narrow">
      <header className="page-header">
        <h1>{t("settings.profileTitle")}</h1>
      </header>
      <NameForm />
      <PasswordForm />
      <TwoFactorSection />
      <ApiTokens />
    </div>
  );
}

function NameForm() {
  const { t } = useTranslation();
  const me = useMe();
  const qc = useQueryClient();
  const toast = useToast();
  const [name, setName] = useState(me.name);
  const save = useMutation({
    mutationFn: () => unwrap(api.PATCH("/api/v1/me", { body: { name } })),
    onSuccess: (m) => {
      qc.setQueryData(["me"], m);
      toast(t("app.saved"));
    },
  });
  const err = save.error ? errorInfo(save.error) : null;
  return (
    <form
      className="row"
      style={{ alignItems: "flex-end" }}
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate();
      }}
    >
      <TextField label={t("settings.yourName")} value={name} onChange={setName} error={err?.fields.name} fieldClassName="spacer" />
      <Button type="submit" variant="primary" busy={save.isPending} disabled={name === me.name}>
        {t("app.save")}
      </Button>
    </form>
  );
}

function PasswordForm() {
  const { t } = useTranslation();
  const me = useMe();
  const toast = useToast();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const change = useMutation({
    mutationFn: () =>
      unwrap(api.POST("/api/v1/me/password", { body: { current_password: me.has_password ? current : undefined, new_password: next } })),
    onSuccess: () => {
      setCurrent("");
      setNext("");
      toast(t("settings.passwordChanged"));
    },
  });
  const err = change.error ? errorInfo(change.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    change.mutate();
  };
  return (
    <section className="section">
      <h2>{me.has_password ? t("settings.changePassword") : t("settings.setPasswordTitle")}</h2>
      <form className="form-grid" onSubmit={submit}>
        {me.has_password && (
          <TextField
            label={t("settings.currentPassword")}
            type="password"
            autoComplete="current-password"
            value={current}
            onChange={setCurrent}
            error={err?.fields.current_password}
          />
        )}
        <TextField
          label={t("settings.newPassword")}
          type="password"
          autoComplete="new-password"
          value={next}
          onChange={setNext}
          error={err?.fields.new_password}
          hint={t("auth.passwordHint")}
        />
        <div className="span-2">
          <Button type="submit" busy={change.isPending} disabled={!next}>
            {me.has_password ? t("settings.changePassword") : t("settings.setPasswordTitle")}
          </Button>
        </div>
      </form>
    </section>
  );
}

type Token = Schemas["ApiTokenView"];

function ApiTokens() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const toast = useToast();
  const tokens = useQuery({ queryKey: ["api_tokens"], queryFn: () => unwrap(api.GET("/api/v1/me/api_tokens")) });
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [readOnly, setReadOnly] = useState(false);
  const [created, setCreated] = useState<string | null>(null);
  const [revoking, setRevoking] = useState<Token | null>(null);

  const create = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/me/api_tokens", { body: { name, scopes: readOnly ? ["read"] : ["read", "write"] } })),
    onSuccess: (res) => {
      setCreated(res.token);
      setName("");
      void qc.invalidateQueries({ queryKey: ["api_tokens"] });
    },
  });
  const revoke = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/me/api_tokens/{id}", { params: { path: { id } } })),
    onSuccess: () => {
      setRevoking(null);
      toast(t("settings.tokenRevoked"));
      void qc.invalidateQueries({ queryKey: ["api_tokens"] });
    },
  });
  const err = create.error ? errorInfo(create.error) : null;

  return (
    <section className="section">
      <div className="row" style={{ marginBottom: 4 }}>
        <h2 className="spacer">{t("settings.apiTokens")}</h2>
        <Button
          size="sm"
          onClick={() => {
            setCreated(null);
            setOpen(true);
          }}
        >
          {t("settings.newToken")}
        </Button>
      </div>
      <p className="muted" style={{ marginBottom: 12 }}>
        {t("settings.apiTokensLead")}
      </p>
      <table className="ledger">
        <thead>
          <tr>
            <th>{t("settings.tokenName")}</th>
            <th>{t("settings.scopes")}</th>
            <th>{t("settings.lastUsed")}</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {tokens.data?.length === 0 && (
            <tr>
              <td colSpan={4} className="muted">
                {t("settings.noTokens")}
              </td>
            </tr>
          )}
          {tokens.data?.map((tok) => (
            <tr key={tok.id}>
              <td>
                {tok.name} <span className="muted">…{tok.token_hint}</span>
              </td>
              <td>{tok.scopes.includes("write") ? "read, write" : t("settings.readOnly")}</td>
              <td>{tok.last_used_at ? formatDateTime(tok.last_used_at) : t("settings.never")}</td>
              <td className="num">
                <Button size="sm" variant="ghost" onClick={() => setRevoking(tok)}>
                  {t("settings.revoke")}
                </Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <Dialog open={open} onOpenChange={setOpen} title={t("settings.newToken")}>
        {created ? (
          <div className="stack">
            <p className="notice notice-warn">{t("settings.tokenCreated")}</p>
            <input className="input" readOnly value={created} onFocus={(e) => e.currentTarget.select()} aria-label={t("settings.apiTokens")} />
            <DialogActions>
              <Button
                onClick={() => {
                  void navigator.clipboard?.writeText(created);
                  toast(t("settings.copied"));
                }}
              >
                {t("settings.copy")}
              </Button>
              <Button variant="primary" onClick={() => setOpen(false)}>
                {t("app.close")}
              </Button>
            </DialogActions>
          </div>
        ) : (
          <form
            className="stack"
            onSubmit={(e) => {
              e.preventDefault();
              create.mutate();
            }}
          >
            <TextField label={t("settings.tokenName")} hint={t("settings.tokenNameHint")} value={name} onChange={setName} error={err?.fields.name} autoFocus />
            <Checkbox checked={readOnly} onChange={setReadOnly} label={t("settings.readOnly")} hint={t("settings.readOnlyHint")} />
            <DialogActions>
              <Button onClick={() => setOpen(false)}>{t("app.cancel")}</Button>
              <Button type="submit" variant="primary" busy={create.isPending}>
                {t("app.create")}
              </Button>
            </DialogActions>
          </form>
        )}
      </Dialog>

      <Dialog open={revoking !== null} onOpenChange={(o) => !o && setRevoking(null)} title={t("settings.revoke")}>
        <p>{t("settings.revokeConfirm", { name: revoking?.name })}</p>
        <DialogActions>
          <Button onClick={() => setRevoking(null)}>{t("app.cancel")}</Button>
          <Button variant="danger" busy={revoke.isPending} onClick={() => revoking && revoke.mutate(revoking.id)}>
            {t("settings.revoke")}
          </Button>
        </DialogActions>
      </Dialog>
    </section>
  );
}
