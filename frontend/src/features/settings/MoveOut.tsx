// SPDX-License-Identifier: AGPL-3.0-only
// Move out ("You can always leave" in the Robin's Code): everything ready for leaving, in one
// step, on every plan and in every state of an account. It changes nothing in the account.
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Button, PageHeader, useConfirm, useToast } from "../../design";
import { api, downloadFile, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { formatDate, formatDateTime } from "../../lib/format";
import { useMe, usePermissions } from "../../lib/session";
import { accountQuery } from "./AccountSettingsPage";

type Export = Schemas["AccountExportView"];
type Connection = Schemas["ConnectionView"];

const SELF_HOSTING_GUIDE = "https://github.com/honestrobin/time/blob/main/docs/self-host.md";

export const exportsQuery = { queryKey: ["account", "exports"], queryFn: () => unwrap(api.GET("/api/v1/exports")) };
export const connectionsQuery = { queryKey: ["account", "connections"], queryFn: () => unwrap(api.GET("/api/v1/account/connections")) };

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

const busy = (list?: Export[]) => list?.some((e) => e.status === "queued" || e.status === "running") ?? false;

/** Starts moving out: the export, and the list of what's still connected. Changes nothing else. */
function useMoveOut(onDone?: () => void) {
  const toast = useToast();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/account/move_out")),
    onSuccess: (r) => {
      qc.setQueryData(connectionsQuery.queryKey, r.connections);
      void qc.invalidateQueries({ queryKey: exportsQuery.queryKey });
      onDone?.();
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
}

/** In Settings → Account: the Move out button, and the exports made so far. */
export function MoveOutSection() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const moveOut = useMoveOut(() => void navigate({ to: "/settings/move-out" }));
  return (
    <section className="section stack" id="export">
      <h2>{t("moveOut.title")}</h2>
      <p className="muted">{t("moveOut.lead")}</p>
      <div>
        <Button onClick={() => moveOut.mutate()} busy={moveOut.isPending}>
          {t("moveOut.button")}
        </Button>
      </div>
      <ExportList />
    </section>
  );
}

/** The exports made so far, polled while one is being prepared. */
function ExportList() {
  const { t } = useTranslation();
  const toast = useToast();
  const list = useQuery({ ...exportsQuery, refetchInterval: (q) => (busy(q.state.data) ? 2000 : false) });
  const download = async (e: Export) => {
    try {
      await downloadFile(`/api/v1/exports/${e.id}/download`);
    } catch (err) {
      toast(errorInfo(err).message, "error");
    }
  };
  if (!list.data?.length) return null;
  return (
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
  );
}

/** The Move out page: the download, where to go next, what's still connected. */
export function MoveOutPage() {
  const { t } = useTranslation();
  const perms = usePermissions();
  const { data: account } = useQuery(accountQuery);
  const exports = useQuery({ ...exportsQuery, enabled: perms.isAdmin });
  const connections = useQuery({ ...connectionsQuery, enabled: perms.isAdmin });
  const moveOut = useMoveOut();
  if (!perms.isAdmin) {
    return (
      <div className="page page-narrow">
        <PageHeader title={t("moveOut.title")} />
        <p className="notice">{t("moveOut.adminOnly")}</p>
      </div>
    );
  }
  // A Honest Robin Cloud subscription is in the list too (the cloud edition only).
  const subscribed = connections.data?.some((c) => c.kind === "subscription") ?? false;
  const readOnly = !!account && account.status !== "active";
  const started = (exports.data?.length ?? 0) > 0;
  return (
    <div className="page page-narrow">
      <PageHeader title={t("moveOut.title")} lead={t("moveOut.pageLead")} />

      <section className="section stack">
        <h2>{t("moveOut.dataTitle")}</h2>
        <p>{t("moveOut.dataLead")}</p>
        <p className="muted">{t("moveOut.dataRedrawn")}</p>
        {(!started || !busy(exports.data)) && (
          <div>
            <Button onClick={() => moveOut.mutate()} busy={moveOut.isPending}>
              {started ? t("moveOut.again") : t("moveOut.button")}
            </Button>
          </div>
        )}
        <ExportList />
      </section>

      <section className="section stack">
        <h2>{t("moveOut.nextTitle")}</h2>
        <div>
          <h3>{t("moveOut.selfHostTitle")}</h3>
          <p>{t("moveOut.selfHostBody")}</p>
          <p>
            <a href={SELF_HOSTING_GUIDE} target="_blank" rel="noreferrer">
              {t("moveOut.selfHostLink")}
            </a>
          </p>
        </div>
        <div>
          <h3>{t("moveOut.toolsTitle")}</h3>
          <p>{t("moveOut.toolsBody")}</p>
        </div>
      </section>

      <section className="section stack" id="connected">
        <h2>{t("moveOut.connectedTitle")}</h2>
        <p className="muted">{t("moveOut.connectedLead")}</p>
        {/* A lapsed account can also deactivate people (decision record 0021); one waiting to be deleted can't. */}
        {readOnly && <p className="notice">{t(account?.status === "lapsed" ? "moveOut.readOnlyLapsed" : "moveOut.readOnly")}</p>}
        {connections.error && <p className="notice notice-error">{errorInfo(connections.error).message}</p>}
        <ConnectionList connections={connections.data} readOnly={readOnly} />
      </section>

      <section className="section stack">
        <h2>{t("moveOut.unchangedTitle")}</h2>
        <p>{t("moveOut.unchangedBody")}</p>
        <ul>
          {subscribed && (
            <li>
              <Link to="/settings/billing">{t("moveOut.cancelSubscription")}</Link>
            </li>
          )}
          <li>
            <Link to="/settings/account" hash="delete">
              {t("moveOut.deleteAccount")}
            </Link>
          </li>
        </ul>
      </section>
    </div>
  );
}

function ConnectionList({ connections, readOnly }: { connections?: Connection[]; readOnly: boolean }) {
  const { t } = useTranslation();
  if (!connections) return <p className="muted">{t("app.loading")}</p>;
  if (connections.length === 0) return <p>{t("moveOut.nothingConnected")}</p>;
  return (
    <div className="table-scroll">
      <table className="ledger">
        <thead>
          <tr>
            <th>{t("moveOut.what")}</th>
            <th>{t("moveOut.howToEnd")}</th>
          </tr>
        </thead>
        <tbody>
          {connections.map((c, i) => (
            <ConnectionRow key={`${c.kind}-${i}`} c={c} readOnly={readOnly} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Row({ title, detail, how, link }: { title: string; detail?: string; how: string; link?: ReactNode }) {
  return (
    <tr>
      <td>
        <strong>{title}</strong>
        {detail && <div className="muted small">{detail}</div>}
      </td>
      <td>
        {how}
        {link && <div className="small">{link}</div>}
      </td>
    </tr>
  );
}

/** The Team plan: its billing interval and status, and how it's cancelled (or that it ends by itself). */
function SubscriptionRow({ c }: { c: Connection }) {
  const { t } = useTranslation();
  const k = "moveOut.kind.subscription";
  const date = (at?: string | null) => (at ? formatDate(at.slice(0, 10)) : "");
  const state = c.ends_at
    ? t(`${k}.ends`, { date: date(c.ends_at) })
    : c.status === "past_due"
      ? t(`${k}.pastDue`)
      : c.status === "trialing"
        ? c.renews_at
          ? t(`${k}.trialing`, { date: date(c.renews_at) })
          : t(`${k}.trialingNoDate`)
        : c.renews_at
          ? t(`${k}.renews`, { date: date(c.renews_at) })
          : t(`${k}.active`);
  const how = c.ends_at ? t(`${k}.howCancelled`, { date: date(c.ends_at) }) : c.status === "past_due" ? t(`${k}.howPastDue`) : t(`${k}.how`);
  return (
    <Row
      title={t(`${k}.title`)}
      detail={`${c.interval === "year" ? t(`${k}.yearly`) : t(`${k}.monthly`)} ${state}`}
      how={how}
      link={<Link to="/settings/billing">{t("nav.billing")}</Link>}
    />
  );
}

function ConnectionRow({ c, readOnly }: { c: Connection; readOnly: boolean }) {
  const { t } = useTranslation();
  const me = useMe();
  const k = `moveOut.kind.${c.kind}`;
  const values = {
    name: c.name ?? "",
    person: c.person ?? "",
    count: c.count ?? 0,
    lastUsed: c.last_used_at ? formatDate(c.last_used_at.slice(0, 10)) : "",
    until: c.ends_at ? formatDate(c.ends_at.slice(0, 10)) : "",
  };
  switch (c.kind) {
    case "subscription":
      return <SubscriptionRow c={c} />;
    case "api_token":
    case "device": {
      const mine = c.membership_id === me.current_membership_id;
      const used = c.last_used_at ? t(`${k}.used`, values) : t(`${k}.unused`, values);
      return (
        <Row
          title={t(`${k}.title`, values)}
          detail={used}
          how={mine ? t("moveOut.kind.token.howMine") : t("moveOut.kind.token.howOther", values)}
          link={<RevokeButton c={c} />}
        />
      );
    }
    case "invitation":
      return <Row title={t(`${k}.title`, values)} detail={t(`${k}.detail`, values)} how={t(`${k}.how`, values)} link={<WithdrawButton c={c} readOnly={readOnly} />} />;
    case "stripe":
      return (
        <Row
          title={t(`${k}.title`)}
          detail={t(`${k}.detail`, values)}
          how={c.mode === "connect" ? t(`${k}.howConnect`) : t(`${k}.howOwnKey`)}
          link={<Link to="/settings/payments">{t("nav.payments")}</Link>}
        />
      );
    case "qbo":
    case "xero":
      return <Row title={t(`${k}.title`)} detail={t(`${k}.detail`, values)} how={t("moveOut.kind.books.how")} link={<Link to="/settings/accounting">{t("nav.accounting")}</Link>} />;
    case "storecove":
      return (
        <Row
          title={t(`${k}.title`)}
          detail={c.mode === "connect" ? t(`${k}.detailConnect`) : t(`${k}.detailOwnKey`)}
          how={c.mode === "connect" ? t(`${k}.howConnect`) : t(`${k}.howOwnKey`)}
          link={<Link to="/settings/invoices">{t("nav.invoiceSettings")}</Link>}
        />
      );
    case "harvest_sync":
    case "harvest_import":
      // Straight to that import on the Import page, where it's stopped or cancelled.
      return (
        <Row
          title={t(`${k}.title`)}
          detail={t(`${k}.detail`, values)}
          how={t(`${k}.how`)}
          link={
            <Link to="/settings/import" hash={c.id ? `import-${c.id}` : undefined}>
              {t("nav.import")}
            </Link>
          }
        />
      );
    case "invoice_links":
      return <Row title={t(`${k}.title`)} detail={t(`${k}.detail`, values)} how={t(`${k}.how`)} />;
    case "invoice_reminders":
      return <Row title={t(`${k}.title`)} detail={t(`${k}.detail`)} how={t(`${k}.how`)} link={<Link to="/settings/invoices">{t("nav.invoiceSettings")}</Link>} />;
    default:
      // A kind this version of the app doesn't know yet: still listed, never hidden.
      return <Row title={c.name ?? c.kind} how={t("moveOut.kind.other.how")} />;
  }
}

/** Ends anyone's token or signed-in device in the account (admins), also while it's read-only. */
function RevokeButton({ c }: { c: Connection }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const revoke = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/account/api_tokens/{id}", { params: { path: { id } } })),
    onSuccess: () => {
      toast(t("settings.tokenRevoked"));
      void qc.invalidateQueries({ queryKey: connectionsQuery.queryKey });
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const [dialog, ask] = useConfirm({ title: t("settings.revoke"), body: t("settings.revokeConfirm", { name: c.name ?? "" }), confirmLabel: t("settings.revoke") });
  const id = c.id;
  if (!id) return null;
  return (
    <>
      {dialog}
      <Button size="sm" variant="ghost" busy={revoke.isPending} onClick={() => ask(() => revoke.mutate(id))}>
        {t("settings.revoke")}
      </Button>
    </>
  );
}

/** Withdraws an invitation, so its link stops working (admins), also while the account is read-only. */
function WithdrawButton({ c, readOnly }: { c: Connection; readOnly: boolean }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const withdraw = useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE("/api/v1/people/{id}/invite", { params: { path: { id } } })),
    onSuccess: () => {
      toast(t("moveOut.withdrawn"));
      void qc.invalidateQueries({ queryKey: connectionsQuery.queryKey });
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const [dialog, ask] = useConfirm({
    title: t("moveOut.withdrawTitle", { name: c.person ?? "" }),
    body: readOnly ? `${t("moveOut.withdrawBody")} ${t("moveOut.withdrawBodyReadOnly")}` : t("moveOut.withdrawBody"),
    confirmLabel: t("moveOut.withdraw"),
  });
  const id = c.membership_id;
  if (!id) return null;
  return (
    <>
      {dialog}
      <Button size="sm" variant="ghost" busy={withdraw.isPending} onClick={() => ask(() => withdraw.mutate(id))}>
        {t("moveOut.withdraw")}
      </Button>
    </>
  );
}
