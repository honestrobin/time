// SPDX-License-Identifier: AGPL-3.0-only
// Export, import and deletion of the whole account (spec §13).
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Dialog, DialogActions, TextField, useToast } from "../../design";
import { ApiError, api, authHeaders, downloadFile, errorInfo, setAccountId, unwrap, type Schemas } from "../../lib/api";
import { formatDate, formatDateTime } from "../../lib/format";
import { useMe, usePermissions } from "../../lib/session";
import { accountQuery } from "./AccountSettingsPage";

type Export = Schemas["AccountExportView"];

const exportsQuery = { queryKey: ["account", "exports"], queryFn: () => unwrap(api.GET("/api/v1/exports")) };

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB"];
  let v = bytes / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(v)} ${units[i]}`;
}

/** Export everything, bring an account over from another instance, or delete this one. */
export function AccountDataSection() {
  const perms = usePermissions();
  if (!perms.isAdmin) return null;
  return (
    <>
      <ExportSection />
      <ImportSection />
      <DeleteSection />
    </>
  );
}

function ExportSection() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const list = useQuery({
    ...exportsQuery,
    // Poll while an export is being prepared.
    refetchInterval: (q) => (q.state.data?.some((e) => e.status === "queued" || e.status === "running") ? 2000 : false),
  });
  const start = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/exports")),
    onSuccess: () => void qc.invalidateQueries({ queryKey: exportsQuery.queryKey }),
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const busy = list.data?.some((e) => e.status === "queued" || e.status === "running") ?? false;
  const download = async (e: Export) => {
    try {
      await downloadFile(`/api/v1/exports/${e.id}/download`);
    } catch (err) {
      toast(errorInfo(err).message, "error");
    }
  };
  return (
    <section className="section stack" id="export">
      <h2>{t("settings.data.exportTitle")}</h2>
      <p className="muted">{t("settings.data.exportLead")}</p>
      <Button onClick={() => start.mutate()} busy={start.isPending || busy} disabled={busy}>
        {busy ? t("settings.data.preparing") : t("settings.data.exportButton")}
      </Button>
      {list.data && list.data.length > 0 && (
        <table className="ledger">
          <tbody>
            {list.data.map((e) => (
              <tr key={e.id}>
                <td>
                  {formatDateTime(e.created_at)}
                  {e.requested_by && <div className="muted small">{t("settings.data.by", { name: e.requested_by })}</div>}
                </td>
                <td>
                  {e.status === "ready" && e.expires_at
                    ? t("settings.data.readyUntil", { size: formatSize(e.size ?? 0), date: formatDate(e.expires_at.slice(0, 10)) })
                    : t(`settings.data.status.${e.status}`)}
                  {e.status === "failed" && e.error && <div className="field-error">{e.error}</div>}
                </td>
                <td className="num">
                  {e.status === "ready" && (
                    <Button size="sm" onClick={() => void download(e)}>
                      {t("settings.data.download")}
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function ImportSection() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const upload = async (file: File) => {
    setBusy(true);
    try {
      const res = await fetch("/api/v1/accounts/import", {
        method: "POST",
        credentials: "include",
        headers: { ...authHeaders(), "Content-Type": "application/zip" },
        body: file,
      });
      const body = await res.json().catch(() => ({ code: `http_${res.status}`, message: res.statusText }));
      if (!res.ok) throw new ApiError(res.status, body);
      toast(t("settings.data.imported", { name: body.name }));
      setAccountId(body.account_id);
      qc.clear();
      location.assign("/");
    } catch (e) {
      toast(errorInfo(e).message, "error");
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  };
  return (
    <section className="section stack" id="import">
      <h2>{t("settings.data.importTitle")}</h2>
      <p className="muted">{t("settings.data.importLead")}</p>
      <input
        ref={input}
        type="file"
        accept=".zip,application/zip"
        hidden
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) void upload(f);
        }}
      />
      <Button onClick={() => input.current?.click()} busy={busy}>
        {t("settings.data.importButton")}
      </Button>
    </section>
  );
}

function DeleteSection() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const { data: account } = useQuery(accountQuery);
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState("");
  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: accountQuery.queryKey }), qc.invalidateQueries({ queryKey: ["me"] })]);
  const schedule = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/account/deletion", { body: { confirm_name: typed } })),
    onSuccess: async () => {
      setOpen(false);
      setTyped("");
      await refresh();
    },
  });
  const cancel = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/account/deletion")),
    onSuccess: async () => {
      await refresh();
      toast(t("settings.data.cancelled"));
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  if (!account) return null;
  const pending = account.status === "pending_deletion";
  const err = schedule.error ? errorInfo(schedule.error) : null;
  return (
    <section className="section stack danger-zone" id="delete">
      <h2>{t("settings.data.deleteTitle")}</h2>
      {pending && account.deletes_at ? (
        <>
          <p className="notice notice-warn">{t("settings.data.pending", { date: formatDate(account.deletes_at.slice(0, 10), "long") })}</p>
          <Button onClick={() => cancel.mutate()} busy={cancel.isPending}>
            {t("settings.data.cancelDeletion")}
          </Button>
        </>
      ) : (
        <>
          <p className="muted">{t("settings.data.deleteLead")}</p>
          <Button variant="danger" onClick={() => setOpen(true)}>
            {t("settings.data.deleteButton")}
          </Button>
        </>
      )}
      <Dialog open={open} onOpenChange={setOpen} title={t("settings.data.deleteDialogTitle", { name: account.name })} description={t("settings.data.deleteDialogBody")}>
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            schedule.mutate();
          }}
        >
          <p>
            <Link to="/settings/account" hash="export" onClick={() => setOpen(false)}>
              {t("settings.data.exportFirst")}
            </Link>
          </p>
          <TextField
            label={t("settings.data.typeName", { name: account.name })}
            value={typed}
            onChange={setTyped}
            autoComplete="off"
            error={err?.fields.confirm_name ?? (err && !Object.keys(err.fields).length ? err.message : undefined)}
          />
          <DialogActions>
            <Button onClick={() => setOpen(false)}>{t("app.cancel")}</Button>
            <Button type="submit" variant="danger" disabled={typed.trim() !== account.name.trim()} busy={schedule.isPending}>
              {t("settings.data.deleteConfirm")}
            </Button>
          </DialogActions>
        </form>
      </Dialog>
    </section>
  );
}

/** Shown on every page while the account is read-only: lapsed, or about to be deleted. */
export function AccountStatusBanner() {
  const { t } = useTranslation();
  const perms = usePermissions();
  const { data: account } = useQuery({ ...accountQuery, staleTime: 60_000 });
  if (!account || account.status === "active") return null;
  const pending = account.status === "pending_deletion" && account.deletes_at;
  return (
    <div className="account-banner" role="status">
      {pending ? t("settings.data.bannerPending", { date: formatDate(account.deletes_at!.slice(0, 10), "long") }) : t("settings.data.bannerReadOnly")}
      {perms.isAdmin && (
        <Link to="/settings/account" hash={pending ? "delete" : "export"}>
          {pending ? t("settings.data.bannerCancel") : t("settings.data.bannerExport")}
        </Link>
      )}
    </div>
  );
}

/** Until the address is confirmed (where sign-up is open), the account can't email anyone. */
export function EmailVerificationBanner() {
  const { t } = useTranslation();
  const toast = useToast();
  const me = useMe();
  const resend = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/auth/verify_email/resend")),
    onSuccess: () => toast(t("auth.verifySent", { email: me.email })),
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  if (!me.email_verification_required || me.email_verified) return null;
  return (
    <div className="account-banner" role="status">
      {t("auth.verifyBanner", { email: me.email })}
      <button type="button" className="link-button" onClick={() => resend.mutate()} disabled={resend.isPending || resend.isSuccess}>
        {resend.isSuccess ? t("auth.verifyResent") : t("auth.verifyResend")}
      </button>
    </div>
  );
}
