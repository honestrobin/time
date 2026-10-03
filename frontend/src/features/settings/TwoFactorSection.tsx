// SPDX-License-Identifier: AGPL-3.0-only
// Two-factor sign-in on the profile page: set up with an authenticator app, recovery codes, turn off.
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Dialog, DialogActions, TextField, useToast } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { formatDate } from "../../lib/format";

const statusQuery = { queryKey: ["two_factor"], queryFn: () => unwrap(api.GET("/api/v1/me/two_factor")) };

export function TwoFactorSection() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const toast = useToast();
  const status = useQuery(statusQuery);
  const [settingUp, setSettingUp] = useState(false);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [confirmOff, setConfirmOff] = useState(false);
  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: ["two_factor"] }), qc.invalidateQueries({ queryKey: ["me"] })]);
  const disable = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/me/two_factor")),
    onSuccess: async () => {
      setConfirmOff(false);
      await refresh();
      toast(t("settings.twoFactorOff"));
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const regenerate = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/me/two_factor/recovery_codes")),
    onSuccess: async (r) => {
      setCodes(r.recovery_codes);
      await refresh();
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  const s = status.data;
  return (
    <section className="section">
      <h2>{t("settings.twoFactorTitle")}</h2>
      {codes ? (
        <RecoveryCodesView codes={codes} onDone={() => setCodes(null)} />
      ) : settingUp ? (
        <TwoFactorSetup
          onEnabled={async (c) => {
            setSettingUp(false);
            setCodes(c);
            await refresh();
          }}
          onCancel={() => setSettingUp(false)}
        />
      ) : s?.enabled ? (
        <div className="stack">
          <p className="notice notice-ok">{t("settings.twoFactorOn", { date: s.enabled_at ? formatDate(s.enabled_at) : "" })}</p>
          <p className={s.recovery_codes_left <= 3 ? "notice notice-warn" : "muted"}>{t("settings.recoveryLeft", { count: s.recovery_codes_left })}</p>
          {s.required_by.length > 0 && <p className="muted small">{t("settings.twoFactorRequiredBy", { accounts: s.required_by.join(", ") })}</p>}
          <div className="row">
            <Button onClick={() => regenerate.mutate()} busy={regenerate.isPending}>
              {t("settings.newRecoveryCodes")}
            </Button>
            {s.required_by.length === 0 && (
              <Button variant="danger" onClick={() => setConfirmOff(true)}>
                {t("settings.twoFactorTurnOff")}
              </Button>
            )}
          </div>
        </div>
      ) : (
        <div className="stack">
          <p className="muted">{t("settings.twoFactorLead")}</p>
          <div>
            <Button variant="primary" onClick={() => setSettingUp(true)} disabled={!s}>
              {t("settings.twoFactorSetUp")}
            </Button>
          </div>
        </div>
      )}
      <Dialog open={confirmOff} onOpenChange={setConfirmOff} title={t("settings.twoFactorTurnOffTitle")} description={t("settings.twoFactorTurnOffLead")}>
        <DialogActions>
          <Button onClick={() => setConfirmOff(false)}>{t("app.cancel")}</Button>
          <Button variant="danger" busy={disable.isPending} onClick={() => disable.mutate()}>
            {t("settings.twoFactorTurnOff")}
          </Button>
        </DialogActions>
      </Dialog>
    </section>
  );
}

/** Scan, confirm with a first code; hands the recovery codes to [onEnabled]. */
export function TwoFactorSetup({ onEnabled, onCancel }: { onEnabled: (codes: string[]) => void; onCancel?: () => void }) {
  const { t } = useTranslation();
  const [code, setCode] = useState("");
  const setup = useQuery({
    queryKey: ["two_factor", "setup"],
    queryFn: () => unwrap(api.POST("/api/v1/me/two_factor/setup")),
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  });
  const enable = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/me/two_factor/enable", { body: { code } })),
    onSuccess: (r) => onEnabled(r.recovery_codes),
  });
  const err = enable.error ? errorInfo(enable.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    enable.mutate();
  };
  if (setup.error) {
    return (
      <div className="stack">
        <p className="notice notice-error">{errorInfo(setup.error).message}</p>
        {onCancel && (
          <div>
            <Button onClick={onCancel}>{t("app.back")}</Button>
          </div>
        )}
      </div>
    );
  }
  const s: Schemas["TwoFactorSetup"] | undefined = setup.data;
  if (!s) return <p className="muted">{t("app.loading")}</p>;
  return (
    <form className="two-factor-setup" onSubmit={submit}>
      <ol className="steps">
        <li>
          <p>{t("settings.twoFactorScan")}</p>
          <img className="qr" src={`data:image/svg+xml;utf8,${encodeURIComponent(s.qr_svg)}`} alt={t("settings.twoFactorQrAlt")} width={184} height={184} />
          <details>
            <summary className="small">{t("settings.twoFactorCantScan")}</summary>
            <p className="small">{t("settings.twoFactorKeyHint")}</p>
            <code className="secret">{s.secret}</code>
          </details>
        </li>
        <li>
          <TextField
            label={t("settings.twoFactorEnterCode")}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={7}
            value={code}
            onChange={setCode}
            error={err?.fields.code ?? (err && !Object.keys(err.fields).length ? err.message : undefined)}
            placeholder="123456"
          />
        </li>
      </ol>
      <div className="row">
        <Button type="submit" variant="primary" busy={enable.isPending} disabled={code.replace(/\D/g, "").length !== 6}>
          {t("settings.twoFactorTurnOn")}
        </Button>
        {onCancel && <Button onClick={onCancel}>{t("app.cancel")}</Button>}
      </div>
    </form>
  );
}

export function RecoveryCodesView({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const text = codes.join("\n");
  const download = () => {
    const url = URL.createObjectURL(new Blob([`${t("settings.recoveryFileHeader")}\n\n${text}\n`], { type: "text/plain" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = "honest-robin-recovery-codes.txt";
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  };
  return (
    <div className="stack">
      <p className="notice notice-warn">{t("settings.recoveryCodesLead")}</p>
      <ul className="recovery-codes">
        {codes.map((c) => (
          <li key={c}>
            <code>{c}</code>
          </li>
        ))}
      </ul>
      <div className="row">
        <Button
          onClick={() =>
            void navigator.clipboard.writeText(text).then(
              () => toast(t("settings.copied")),
              () => undefined,
            )
          }
        >
          {t("settings.copy")}
        </Button>
        <Button onClick={download}>{t("settings.download")}</Button>
        <Button variant="primary" onClick={onDone}>
          {t("settings.recoverySaved")}
        </Button>
      </div>
    </div>
  );
}
