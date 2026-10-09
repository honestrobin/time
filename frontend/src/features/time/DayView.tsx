// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, EmptyState, Kbd } from "../../design";
import { api, unwrap } from "../../lib/api";
import { startOfWeek, weekDays } from "../../lib/dates";
import { formatDate, formatDuration, formatWeekday } from "../../lib/format";
import { useFeature } from "../../lib/features";
import { useHotkeys } from "../../lib/hotkeys";
import { useMe } from "../../lib/session";
import { EntryDialog } from "./EntryDialog";
import { liveSeconds, useAccountSettings, useInvalidateTime, useNow, type TimeEntry } from "./hooks";

const LONG_ENTRY = 12 * 3600;

export function DayView({ date }: { date: string }) {
  const { t } = useTranslation();
  const approvals = useFeature("approvals");
  const me = useMe();
  const account = useAccountSettings();
  const invalidate = useInvalidateTime();
  const weekStart = startOfWeek(date, account.weekStart);
  const days = weekDays(weekStart);
  const q = useQuery({
    queryKey: ["time_entries", "week", weekStart, me.current_membership_id],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/time_entries", {
          params: { query: { from: days[0], to: days[6], membership_id: me.current_membership_id ?? undefined, limit: 1000 } },
        }),
      ),
    enabled: account.loaded,
  });
  const entries = q.data?.data ?? [];
  const running = entries.some((e) => e.is_running);
  const now = useNow(running);
  const secondsOf = (e: TimeEntry) => liveSeconds(e, now, q.dataUpdatedAt);
  const today = account.today;
  const dayEntries = entries.filter((e) => e.spent_date === date).sort((a, b) => a.created_at.localeCompare(b.created_at));
  const style = account.durationStyle;

  const [dialog, setDialog] = useState<{ open: boolean; entry: TimeEntry | null }>({ open: false, entry: null });
  const last = [...entries].sort((a, b) => b.updated_at.localeCompare(a.updated_at))[0];

  const toggle = useMutation({
    mutationFn: (e: TimeEntry) =>
      e.is_running
        ? unwrap(api.POST("/api/v1/time_entries/{id}/stop", { params: { path: { id: e.id } } }))
        : unwrap(api.POST("/api/v1/time_entries/{id}/start", { params: { path: { id: e.id } } })),
    onSuccess: () => invalidate(),
  });

  useHotkeys({
    n: () => setDialog({ open: true, entry: null }),
    s: () => {
      const r = entries.find((e) => e.is_running);
      if (r) toggle.mutate(r);
    },
  });

  const dayTotal = dayEntries.reduce((s, e) => s + secondsOf(e), 0);
  const weekTotal = entries.reduce((s, e) => s + secondsOf(e), 0);

  return (
    <>
      <nav className="week-strip" aria-label={t("time.week")}>
        {days.map((d) => {
          const total = entries.filter((e) => e.spent_date === d).reduce((s, e) => s + secondsOf(e), 0);
          return (
            <Link key={d} to="/day/$date" params={{ date: d }} className="week-day" aria-current={d === date ? "date" : undefined}>
              <span className="d-name">
                {formatWeekday(d)} {Number(d.slice(8))}
              </span>
              <span className={total ? "d-total" : "d-total zero"}>{formatDuration(total, style)}</span>
              {d === today && <span className="d-today">{t("time.today")}</span>}
            </Link>
          );
        })}
        <div className="week-sum">
          <span className="muted" style={{ fontSize: "var(--text-sm)" }}>
            {t("time.weekTotal")}
          </span>
          <span className="d-total">{formatDuration(weekTotal, style)}</span>
        </div>
      </nav>

      <div className="row" style={{ marginBottom: 12 }}>
        <Button variant="primary" onClick={() => setDialog({ open: true, entry: null })}>
          {date === today ? t("time.trackTime") : t("time.newEntry")} <Kbd>N</Kbd>
        </Button>
      </div>

      {dayEntries.length === 0 && !q.isLoading ? (
        <EmptyState robin="nest" title={t("time.nothingTracked", { date: formatDate(date, "long") })} body={t("time.nothingTrackedBody")} />
      ) : (
        <div className="table-scroll">
          <table className="ledger day-grid">
            <tbody>
              {dayEntries.map((e) => {
                const seconds = secondsOf(e);
                return (
                  <tr key={e.id}>
                    <td className="entry-what">
                      <strong>
                        {e.project.code ? `[${e.project.code}] ` : ""}
                        {e.project.name} <span className="muted" style={{ fontWeight: 400 }}>({e.client.name})</span>
                      </strong>
                      <span className="entry-sub">
                        {e.task.name}
                        {!e.billable && (
                          <span className="badge" style={{ marginLeft: 8 }}>
                            {t("time.nonBillable")}
                          </span>
                        )}
                      </span>
                      {e.notes && <div className="entry-notes">{e.notes}</div>}
                      {e.external_reference?.url && (
                        <a href={e.external_reference.url} target="_blank" rel="noreferrer" style={{ fontSize: "var(--text-sm)" }}>
                          {e.external_reference.title || t("time.external")}
                        </a>
                      )}
                      {seconds > LONG_ENTRY && <div className="field-error">{t("time.longEntry")}</div>}
                    </td>
                    <td style={{ width: 120 }}>
                      <StateBadge entry={e} />
                    </td>
                    <td className={e.is_running ? "entry-duration running" : "entry-duration"} style={{ width: 110 }}>
                      {formatDuration(seconds, style)}
                    </td>
                    <td style={{ width: 180 }}>
                      <div className="entry-actions">
                        {!e.is_locked && !(approvals && e.approval_state === "submitted") && (
                          <Button size="sm" variant={e.is_running ? "danger" : "secondary"} onClick={() => toggle.mutate(e)} busy={toggle.isPending && toggle.variables?.id === e.id}>
                            {e.is_running ? t("time.stop") : t("time.start")}
                          </Button>
                        )}
                        <Button size="sm" variant="ghost" onClick={() => setDialog({ open: true, entry: e })} disabled={e.is_locked}>
                          {t("app.edit")}
                        </Button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
            <tfoot>
              <tr>
                <td colSpan={2} className="num muted">
                  {t("time.total")}
                </td>
                <td className="num total day-total">{formatDuration(dayTotal, style)}</td>
                <td />
              </tr>
            </tfoot>
          </table>
        </div>
      )}

      <EntryDialog
        open={dialog.open}
        onOpenChange={(open) => setDialog((d) => ({ ...d, open }))}
        date={date}
        isToday={date === today}
        entry={dialog.entry}
        durationStyle={style}
        initial={last ? { projectId: last.project.id, taskId: last.task.id } : undefined}
      />
    </>
  );
}

export function StateBadge({ entry }: { entry: Pick<TimeEntry, "approval_state" | "is_locked" | "invoice_id"> }) {
  const { t } = useTranslation();
  // With approvals switched off, a week sent for approval is open again (the server agrees).
  const approvals = useFeature("approvals");
  if (entry.invoice_id) return <span className="badge">{t("time.lockedInvoiced")}</span>;
  if (entry.approval_state === "approved") return <span className="badge badge-ok">{t("time.lockedApproved")}</span>;
  if (approvals && entry.approval_state === "submitted") return <span className="badge">{t("time.submitted")}</span>;
  if (approvals && entry.approval_state === "rejected") return <span className="badge badge-warn">{t("time.rejected")}</span>;
  if (entry.is_locked) return <span className="badge">{t("time.locked")}</span>;
  return null;
}
