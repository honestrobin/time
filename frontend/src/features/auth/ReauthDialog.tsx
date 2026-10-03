// SPDX-License-Identifier: AGPL-3.0-only
// "Confirm it's you": shown when a sensitive action needs a recent sign-in (see lib/reauth.ts).
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Dialog, DialogActions, TextField } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { registerReauth } from "../../lib/reauth";
import { useMe } from "../../lib/session";

export function ReauthDialog() {
  const { t } = useTranslation();
  const me = useMe();
  const done = useRef<((confirmed: boolean) => void) | null>(null);
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [linkSent, setLinkSent] = useState(false);
  // Password if there is one, otherwise a code from the app if two-factor is on, otherwise an emailed link.
  const [useCode, setUseCode] = useState(false);
  const mode = me.has_password && !useCode ? "password" : me.two_factor_enabled ? "code" : "link";

  useEffect(
    () =>
      registerReauth((resolve) => {
        done.current = resolve;
        setPassword("");
        setError(undefined);
        setLinkSent(false);
        setUseCode(false);
        setOpen(true);
      }),
    [],
  );

  const finish = (confirmed: boolean) => {
    setOpen(false);
    done.current?.(confirmed);
    done.current = null;
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      await unwrap(api.POST("/api/v1/auth/reauth", { body: mode === "code" ? { code: password } : { password } }));
      finish(true);
    } catch (err) {
      setError(errorInfo(err).message);
    } finally {
      setBusy(false);
    }
  };

  const sendLink = async () => {
    setBusy(true);
    try {
      await unwrap(api.POST("/api/v1/auth/magic_link", { body: { email: me.email } }));
      setLinkSent(true);
    } catch (err) {
      setError(errorInfo(err).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => !o && finish(false)}
      title={t("auth.reauthTitle")}
      description={mode === "password" ? t("auth.reauthLead") : mode === "code" ? t("auth.reauthLeadCode") : t("auth.reauthLeadNoPassword")}
    >
      {linkSent ? (
        <>
          <p className="notice notice-ok">{t("auth.reauthLinkSent", { email: me.email })}</p>
          <DialogActions>
            <Button onClick={() => finish(false)}>{t("app.close")}</Button>
          </DialogActions>
        </>
      ) : mode !== "link" ? (
        <form onSubmit={submit} className="stack">
          {mode === "password" ? (
            <TextField key="password" label={t("auth.password")} type="password" autoComplete="current-password" autoFocus value={password} onChange={setPassword} error={error} />
          ) : (
            <TextField
              key="code"
              label={t("auth.twoFactorCode")}
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={7}
              autoFocus
              value={password}
              onChange={setPassword}
              error={error}
            />
          )}
          <p className="small stack" style={{ gap: 4 }}>
            {me.has_password && me.two_factor_enabled && (
              <button
                type="button"
                className="link-button"
                onClick={() => {
                  setUseCode(!useCode);
                  setPassword("");
                  setError(undefined);
                }}
              >
                {mode === "password" ? t("auth.reauthUseCode") : t("auth.reauthUsePassword")}
              </button>
            )}
            {mode === "password" && (
              <button type="button" className="link-button" onClick={sendLink} disabled={busy}>
                {t("auth.reauthUseLink")}
              </button>
            )}
          </p>
          <DialogActions>
            <Button onClick={() => finish(false)}>{t("app.cancel")}</Button>
            <Button variant="primary" type="submit" busy={busy} disabled={!password}>
              {t("auth.reauthConfirm")}
            </Button>
          </DialogActions>
        </form>
      ) : (
        <>
          {error && <p className="notice notice-error">{error}</p>}
          <DialogActions>
            <Button onClick={() => finish(false)}>{t("app.cancel")}</Button>
            <Button variant="primary" onClick={sendLink} busy={busy}>
              {t("auth.emailLink")}
            </Button>
          </DialogActions>
        </>
      )}
    </Dialog>
  );
}
