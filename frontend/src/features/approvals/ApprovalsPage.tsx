// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Fragment, useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Dialog, DialogActions, EmptyState, LoadingRow, PageHeader, Tabs, TextAreaField, useToast } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { addDays } from "../../lib/dates";
import { formatDate, formatDateTime, formatDuration, formatMoney, formatWeekday } from "../../lib/format";
import { useMe, usePermissions } from "../../lib/session";
import { useAccountSettings } from "../time/hooks";
import "./approvals.css";

type Item = Schemas["ApprovalItem"];
type Tab = "submitted" | "approved" | "rejected";

const TABS: { value: Tab; label: string }[] = [
  { value: "submitted", label: "approvals.tabWaiting" },
  { value: "approved", label: "approvals.tabApproved" },
  { value: "rejected", label: "approvals.tabReturned" },
];

export function ApprovalsPage() {
  const { t } = useTranslation();
  const perms = usePermissions();
  const account = useAccountSettings();
  const [tab, setTab] = useState<Tab>("submitted");

  if (!account.loaded) return <div className="page">{t("app.loading")}</div>;

  return (
    <div className="page">
      <PageHeader title={t("approvals.title")} lead={t("approvals.lead")} />
      {!account.approvalsEnabled ? (
        <EmptyState
          title={t("approvals.offTitle")}
          body={perms.isAdmin ? t("approvals.offBodyAdmin") : t("approvals.offBody")}
          action={
            perms.isAdmin && (
              <Link to="/settings/account" className="btn btn-primary">
                {t("approvals.openSettings")}
              </Link>
            )
          }
        />
      ) : (
        <>
          <Tabs value={tab} onChange={(v) => setTab(v as Tab)} tabs={TABS.map((x) => ({ value: x.value, label: t(x.label) }))} />
          <ApprovalList state={tab} />
        </>
      )}
    </div>
  );
}

function ApprovalList({ state }: { state: Tab }) {
  const { t } = useTranslation();
  const account = useAccountSettings();
  const q = useQuery({
    queryKey: ["approvals", state],
    queryFn: () => unwrap(api.GET("/api/v1/approvals", { params: { query: { state } } })),
  });
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [returning, setReturning] = useState<Item | null>(null);
  const [reopening, setReopening] = useState<Item | null>(null);
  const items = q.data ?? [];
  const style = account.durationStyle;
  const toggle = (id: string) =>
    setOpen((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  if (q.isError) {
    return (
      <p className="notice notice-error" role="alert">
        {errorInfo(q.error).message}
      </p>
    );
  }
  if (!q.isLoading && items.length === 0) {
    return <EmptyState title={t(`approvals.empty.${state}.title`)} body={t(`approvals.empty.${state}.body`)} />;
  }

  const sum = (f: (i: Item) => number) => items.reduce((s, i) => s + f(i), 0);

  return (
    <>
      <table className="ledger">
        <thead>
          <tr>
            <th style={{ width: 40 }}>
              <span className="sr-only">{t("approvals.details")}</span>
            </th>
            <th>{t("approvals.person")}</th>
            <th>{t("approvals.week")}</th>
            <th className="num">{t("approvals.total")}</th>
            <th className="num">{t("approvals.billable")}</th>
            <th className="num">{t("approvals.entries")}</th>
            <th className="num">{t("approvals.expenses")}</th>
            <th>
              <span className="sr-only">{t("approvals.actions")}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {q.isLoading && <LoadingRow colSpan={8} />}
          {items.map((item) => {
            const id = item.submission.id;
            const expanded = open.has(id);
            return (
              <Fragment key={id}>
                <tr className={expanded ? "approval-open" : undefined}>
                  <td>
                    <button
                      type="button"
                      className="expand-btn"
                      aria-expanded={expanded}
                      aria-controls={`approval-${id}`}
                      aria-label={t("approvals.showEntries", { name: item.person.name, week: formatDate(item.submission.week_start_date) })}
                      onClick={() => toggle(id)}
                    >
                      <span aria-hidden>▸</span>
                    </button>
                  </td>
                  <td>{item.person.name}</td>
                  <td>
                    {t("approvals.weekOf", { date: formatDate(item.submission.week_start_date) })}
                    <Decision item={item} />
                  </td>
                  <td className="num">{formatDuration(item.total_seconds, style)}</td>
                  <td className="num">{formatDuration(item.billable_seconds, style)}</td>
                  <td className="num">{item.entry_count}</td>
                  <td className="num">{item.expense_count}</td>
                  <td>
                    <RowActions item={item} onReturn={() => setReturning(item)} onReopen={() => setReopening(item)} />
                  </td>
                </tr>
                {expanded && (
                  <tr className="approval-detail" id={`approval-${id}`}>
                    <td colSpan={8}>
                      <WeekDetail item={item} />
                    </td>
                  </tr>
                )}
              </Fragment>
            );
          })}
        </tbody>
        {items.length > 1 && (
          <tfoot>
            <tr>
              <td />
              <td colSpan={2} className="muted">
                {t("approvals.weekCount", { count: items.length })}
              </td>
              <td className="num total">{formatDuration(sum((i) => i.total_seconds), style)}</td>
              <td className="num total">{formatDuration(sum((i) => i.billable_seconds), style)}</td>
              <td className="num total">{sum((i) => i.entry_count)}</td>
              <td className="num total">{sum((i) => i.expense_count)}</td>
              <td />
            </tr>
          </tfoot>
        )}
      </table>
      <ReturnDialog item={returning} onClose={() => setReturning(null)} />
      <ReopenDialog item={reopening} onClose={() => setReopening(null)} />
    </>
  );
}

/** Who decided and when, plus the comment on returned weeks. */
function Decision({ item }: { item: Item }) {
  const { t } = useTranslation();
  const s = item.submission;
  if (s.state === "submitted") {
    return <span className="approval-sub">{t("approvals.submittedOn", { date: formatDateTime(s.submitted_at) })}</span>;
  }
  if (!s.decided_at) return null;
  const who = s.decided_by?.name ?? t("approvals.someone");
  const key = s.state === "approved" ? "approvals.approvedBy" : s.state === "rejected" ? "approvals.returnedBy" : "approvals.reopenedBy";
  return (
    <span className="approval-sub">
      {t(key, { name: who, date: formatDate(s.decided_at) })}
      {s.comment && <> {t("approvals.quoted", { text: s.comment })}</>}
    </span>
  );
}

function useInvalidateApprovals() {
  const qc = useQueryClient();
  return () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: ["approvals"] }),
      qc.invalidateQueries({ queryKey: ["time_entries"] }),
      qc.invalidateQueries({ queryKey: ["week"] }),
      qc.invalidateQueries({ queryKey: ["expenses"] }),
    ]);
}

function RowActions({ item, onReturn, onReopen }: { item: Item; onReturn: () => void; onReopen: () => void }) {
  const { t } = useTranslation();
  const perms = usePermissions();
  const me = useMe();
  const toast = useToast();
  const invalidate = useInvalidateApprovals();
  const approve = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/approvals/{id}/approve", { params: { path: { id: item.submission.id } } })),
    onSuccess: async (res) => {
      await invalidate();
      toast(res.submission.state === "approved" ? t("approvals.approved") : t("approvals.partlyApproved"));
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  const state = item.submission.state;
  if (state === "submitted") {
    const own = item.person.id === me.current_membership_id && !perms.isAdmin;
    const nothingToDecide = !perms.isAdmin && item.decidable_entry_count === 0;
    if (own || nothingToDecide) {
      return <span className="approval-sub" style={{ textAlign: "right" }}>{own ? t("approvals.ownWeek") : t("approvals.notYourProjects")}</span>;
    }
    return (
      <div className="approval-actions">
        <Button size="sm" onClick={onReturn}>
          {t("approvals.return")}
        </Button>
        <Button size="sm" variant="primary" busy={approve.isPending} onClick={() => approve.mutate()}>
          {t("approvals.approve")}
        </Button>
      </div>
    );
  }
  if (state === "approved" && perms.isAdmin) {
    return (
      <div className="approval-actions">
        <Button size="sm" onClick={onReopen}>
          {t("approvals.reopen")}
        </Button>
      </div>
    );
  }
  return null;
}

/* ---- Expanded row: the week's entries and expenses ----------------------------------------- */

function WeekDetail({ item }: { item: Item }) {
  const { t } = useTranslation();
  const perms = usePermissions();
  const account = useAccountSettings();
  const from = item.submission.week_start_date;
  const to = addDays(from, 6);
  const membershipId = item.person.id;
  const entries = useQuery({
    queryKey: ["time_entries", "approval", membershipId, from],
    queryFn: () => unwrap(api.GET("/api/v1/time_entries", { params: { query: { membership_id: membershipId, from, to, limit: 1000 } } })),
  });
  const expenses = useQuery({
    queryKey: ["expenses", "approval", membershipId, from],
    queryFn: () => unwrap(api.GET("/api/v1/expenses", { params: { query: { membership_id: membershipId, from, to, limit: 500 } } })),
    enabled: item.expense_count > 0,
  });
  const rows = [...(entries.data?.data ?? [])].sort((a, b) => a.spent_date.localeCompare(b.spent_date) || a.created_at.localeCompare(b.created_at));
  const style = account.durationStyle;
  const partial = !perms.isAdmin && item.submission.state === "submitted" && item.decidable_entry_count < item.entry_count;

  return (
    <div className="approval-detail-body">
      <div className="row" style={{ marginBottom: 8 }}>
        <span className="spacer muted" style={{ fontSize: "var(--text-sm)" }}>
          {formatDate(from)} – {formatDate(to)}
        </span>
        <Link to="/week/$date" params={{ date: from }} search={{ person: membershipId }} style={{ fontSize: "var(--text-sm)" }}>
          {t("approvals.openTimesheet")}
        </Link>
      </div>
      {partial && (
        <p className="notice notice-warn" style={{ marginBottom: 8 }}>
          {t("approvals.partial", { count: item.decidable_entry_count, total: item.entry_count })}
        </p>
      )}
      {entries.isError ? (
        <p className="field-error">{errorInfo(entries.error).message}</p>
      ) : entries.isLoading ? (
        <p className="muted">{t("app.loading")}</p>
      ) : rows.length === 0 ? (
        <p className="muted">{t("approvals.noEntries")}</p>
      ) : (
        <table className="ledger">
          <thead>
            <tr>
              <th>{t("approvals.date")}</th>
              <th>{t("approvals.project")}</th>
              <th>{t("approvals.task")}</th>
              <th>{t("approvals.notes")}</th>
              <th className="num">{t("approvals.time")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((e) => (
              <tr key={e.id}>
                <td style={{ whiteSpace: "nowrap" }}>
                  {formatWeekday(e.spent_date)} {formatDate(e.spent_date, "short")}
                </td>
                <td>
                  {e.project.name} <span className="muted">({e.client.name})</span>
                </td>
                <td>
                  {e.task.name}
                  {!e.billable && (
                    <span className="badge" style={{ marginLeft: 6 }}>
                      {t("approvals.notBillable")}
                    </span>
                  )}
                </td>
                <td className="muted">{e.notes}</td>
                <td className="num">{formatDuration(e.duration_seconds, style)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {item.expense_count > 0 && (
        <>
          <h3>{t("approvals.expenses")}</h3>
          {expenses.isLoading ? (
            <p className="muted">{t("app.loading")}</p>
          ) : (
            <table className="ledger">
              <tbody>
                {(expenses.data?.data ?? []).map((x) => (
                  <tr key={x.id}>
                    <td style={{ whiteSpace: "nowrap" }}>
                      {formatWeekday(x.spent_date)} {formatDate(x.spent_date, "short")}
                    </td>
                    <td>
                      {x.project.name} <span className="muted">({x.client.name})</span>
                    </td>
                    <td>{x.category.name}</td>
                    <td className="muted">{x.notes}</td>
                    <td className="num">{formatMoney(x.amount, x.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </div>
  );
}

/* ---- Dialogs ------------------------------------------------------------------------------ */

function ReturnDialog({ item, onClose }: { item: Item | null; onClose: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const invalidate = useInvalidateApprovals();
  const [comment, setComment] = useState("");
  const reject = useMutation({
    mutationFn: (i: Item) => unwrap(api.POST("/api/v1/approvals/{id}/reject", { params: { path: { id: i.submission.id } }, body: { comment } })),
    onSuccess: async () => {
      await invalidate();
      toast(t("approvals.returned"));
      onClose();
    },
  });
  useEffect(() => {
    if (item) {
      setComment("");
      reject.reset();
    }
  }, [item]);
  const err = reject.error ? errorInfo(reject.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (item) reject.mutate(item);
  };
  return (
    <Dialog
      open={item !== null}
      onOpenChange={(o) => !o && onClose()}
      title={t("approvals.returnTitle", { name: item?.person.name ?? "" })}
      description={item ? t("approvals.weekOf", { date: formatDate(item.submission.week_start_date) }) : undefined}
    >
      <form className="stack" onSubmit={submit}>
        {err && !err.fields.comment && (
          <p className="notice notice-error" role="alert">
            {err.message}
          </p>
        )}
        <p>{t("approvals.returnLead", { name: item?.person.name ?? "" })}</p>
        <TextAreaField
          label={t("approvals.comment")}
          hint={t("approvals.commentHint")}
          value={comment}
          onChange={setComment}
          rows={4}
          error={err?.fields.comment}
          autoFocus
        />
        <DialogActions>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button type="submit" variant="primary" busy={reject.isPending}>
            {t("approvals.returnWeek")}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

function ReopenDialog({ item, onClose }: { item: Item | null; onClose: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const invalidate = useInvalidateApprovals();
  const [reason, setReason] = useState("");
  const reopen = useMutation({
    mutationFn: (i: Item) => unwrap(api.POST("/api/v1/approvals/{id}/reopen", { params: { path: { id: i.submission.id } }, body: { reason } })),
    onSuccess: async () => {
      await invalidate();
      toast(t("approvals.reopened"));
      onClose();
    },
  });
  useEffect(() => {
    if (item) {
      setReason("");
      reopen.reset();
    }
  }, [item]);
  const err = reopen.error ? errorInfo(reopen.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (item && reason.trim()) reopen.mutate(item);
  };
  return (
    <Dialog
      open={item !== null}
      onOpenChange={(o) => !o && onClose()}
      title={t("approvals.reopenTitle", { name: item?.person.name ?? "" })}
      description={item ? t("approvals.weekOf", { date: formatDate(item.submission.week_start_date) }) : undefined}
    >
      <form className="stack" onSubmit={submit}>
        {err && !err.fields.reason && (
          <p className="notice notice-error" role="alert">
            {err.message}
          </p>
        )}
        <p>{t("approvals.reopenLead")}</p>
        <TextAreaField
          label={t("approvals.reason")}
          hint={t("approvals.reasonHint")}
          value={reason}
          onChange={setReason}
          rows={3}
          error={err?.fields.reason}
          required
          autoFocus
        />
        <DialogActions>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button type="submit" variant="primary" busy={reopen.isPending} disabled={!reason.trim()}>
            {t("approvals.reopen")}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
