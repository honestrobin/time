// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { api, errorInfo, unwrap } from "../../lib/api";
import { startOfWeek, weekDays } from "../../lib/dates";
import { formatDuration, formatWeekday } from "../../lib/format";
import { useFeature } from "../../lib/features";
import { useHotkeys } from "../../lib/hotkeys";
import { useMe } from "../../lib/session";
import { EntryDialog } from "./EntryDialog";
import { liveSeconds, useAccountSettings, useInvalidateTime, useNow, useRunningTimer, type TimeEntry } from "./hooks";
import { TimerCard } from "./TimerCard";
import { entryRange, fillPercents } from "./track";

const LONG_ENTRY = 12 * 3600;

type DialogState = { open: boolean; entry: TimeEntry | null; notes?: string; projectId?: string; newProject?: boolean };

/** Track, one day: today's timer, the week filling up, and the day's entries with their total. */
export function DayView({ date }: { date: string }) {
  const { t } = useTranslation();
  const approvals = useFeature("approvals");
  const tasks = useFeature("tasks");
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
  const timer = useRunningTimer();
  const entries = q.data?.data ?? [];
  const running = entries.some((e) => e.is_running);
  const now = useNow(running);
  const secondsOf = (e: TimeEntry) => liveSeconds(e, now, q.dataUpdatedAt);
  const today = account.today;
  const isToday = date === today;
  const dayEntries = entries.filter((e) => e.spent_date === date).sort((a, b) => a.created_at.localeCompare(b.created_at));
  const style = account.durationStyle;
  // The entry touched last this week, or else the newest one of any week (the list is newest first),
  // so a new week's first timer still starts on the project used last.
  const latest = useQuery({
    queryKey: ["time_entries", "latest", me.current_membership_id],
    queryFn: () => unwrap(api.GET("/api/v1/time_entries", { params: { query: { membership_id: me.current_membership_id ?? undefined, limit: 1 } } })),
    enabled: account.loaded && isToday,
  });
  const recent = [...entries].sort((a, b) => b.updated_at.localeCompare(a.updated_at))[0] ?? latest.data?.data[0];

  const [dialog, setDialog] = useState<DialogState>({ open: false, entry: null });
  const addByHand = () => setDialog({ open: true, entry: null });

  // Continue starts a new entry with the same project, task and note, so every range stays true.
  const continueEntry = useMutation({
    mutationFn: (e: TimeEntry) =>
      unwrap(
        api.POST("/api/v1/time_entries", {
          body: { project_id: e.project.id, task_id: e.task.id, spent_date: today, notes: e.notes ?? undefined, billable: e.billable },
        }),
      ),
    onSuccess: () => invalidate(),
  });
  const stop = useMutation({
    mutationFn: (id: string) => unwrap(api.POST("/api/v1/time_entries/{id}/stop", { params: { path: { id } } })),
    onSuccess: () => invalidate(),
  });

  // On today, N and S belong to the timer; on other days N adds time to that day.
  useHotkeys(
    {
      n: addByHand,
      s: () => {
        if (timer.entry) stop.mutate(timer.entry.id);
      },
    },
    !isToday,
  );

  const totals = days.map((d) => entries.filter((e) => e.spent_date === d).reduce((s, e) => s + secondsOf(e), 0));
  const fills = fillPercents(totals);
  const dayTotal = dayEntries.reduce((s, e) => s + secondsOf(e), 0);
  const weekday = formatWeekday(date, "long");

  return (
    <>
      {isToday && (
        <TimerCard
          running={timer.entry}
          fetchedAt={timer.fetchedAt}
          recent={recent}
          onEdit={(entry) => setDialog({ open: true, entry })}
          onNeedDetails={(start) => setDialog({ open: true, entry: null, ...start })}
        />
      )}

      <nav className="week-days" aria-label={t("time.thisWeek")}>
        {days.map((d, i) => (
          // The router marks the link for the address you're on as the page, so a class shades the
          // chosen day whichever address got you here ("/" or "/day/…").
          <Link key={d} to="/day/$date" params={{ date: d }} className={d === date ? "week-day is-selected" : "week-day"} aria-current={d === date ? "date" : undefined}>
            <span className="d-name">
              {formatWeekday(d)} {Number(d.slice(8))}
            </span>
            <span className={totals[i] ? "d-total" : "d-total is-zero"}>{totals[i] ? formatDuration(totals[i], style) : "–"}</span>
            <span className="d-fill" aria-hidden>
              <span style={{ width: `${fills[i]}%` }} />
            </span>
          </Link>
        ))}
      </nav>

      <section className="day-entries" aria-labelledby="day-entries-title">
        <div className="day-entries-head">
          <h2 id="day-entries-title">{t("time.dayEntries", { weekday })}</h2>
          <button type="button" className="link-button" onClick={addByHand}>
            + {t("time.addByHand")}
          </button>
        </div>

        {dayEntries.length === 0 && !q.isLoading ? (
          <p className="day-empty">{isToday ? t("time.emptyToday") : t("time.emptyDay", { weekday })}</p>
        ) : (
          <div className={dayEntries.some((e) => e.start_time) ? "entries" : "entries no-ranges"}>
            <ul>
              {dayEntries.map((e) => {
                const seconds = secondsOf(e);
                const range = entryRange(e, t("time.now"));
                const editable = !e.is_locked;
                const what = (
                  <>
                    <span className="entry-title">
                      {e.client.name} · {e.project.name}
                      {!e.billable && <span className="badge">{t("time.nonBillable")}</span>}
                    </span>
                    {(e.notes || tasks) && <span className="entry-note">{[tasks ? e.task.name : null, e.notes].filter(Boolean).join(" · ")}</span>}
                  </>
                );
                return (
                  <li key={e.id} className={e.is_running ? "entry is-running" : "entry"}>
                    <span className="entry-range">{range}</span>
                    <div className="entry-what">
                      {editable ? (
                        <button type="button" className="entry-open" aria-haspopup="dialog" onClick={() => setDialog({ open: true, entry: e })}>
                          {what}
                        </button>
                      ) : (
                        <span className="entry-open">{what}</span>
                      )}
                      {e.external_reference?.url && (
                        <a className="entry-link" href={e.external_reference.url} target="_blank" rel="noreferrer">
                          {e.external_reference.title || t("time.external")}
                        </a>
                      )}
                      {seconds > LONG_ENTRY && <span className="field-error">{t("time.longEntry")}</span>}
                    </div>
                    <span className="entry-state">
                      {e.is_running ? (
                        <span className="running-label">
                          <span className="live-dot small" aria-hidden />
                          {t("time.running")}
                        </span>
                      ) : (
                        <StateBadge entry={e} />
                      )}
                      {isToday && !running && !e.is_running && !(approvals && e.approval_state === "submitted") && (
                        <button type="button" className="entry-continue" onClick={() => continueEntry.mutate(e)} disabled={continueEntry.isPending}>
                          ▶ {t("time.continue")}
                        </button>
                      )}
                    </span>
                    <span className="entry-duration">{formatDuration(seconds, style)}</span>
                  </li>
                );
              })}
            </ul>
            <div className="entries-total">
              <span>{isToday ? t("time.today") : t("time.total")}</span>
              <span className="entries-total-sum">{formatDuration(dayTotal, style)}</span>
            </div>
          </div>
        )}
        {continueEntry.error && (
          <p className="timer-error" role="alert">
            {errorInfo(continueEntry.error).message}
          </p>
        )}
      </section>

      <p className="keys-hint">
        {t("time.keysLabel")} <b>N</b> {isToday ? t("time.keys.start") : t("time.keys.add")} · <b>S</b> {t("time.keys.stop")} · <b>D</b> {t("time.keys.day")} · <b>W</b>{" "}
        {t("time.keys.week")} · <b>← →</b> {t("time.keys.move")}
      </p>

      <EntryDialog
        open={dialog.open}
        onOpenChange={(open) => setDialog((d) => ({ ...d, open }))}
        date={date}
        isToday={isToday}
        entry={dialog.entry}
        durationStyle={style}
        initial={
          dialog.projectId || dialog.notes || dialog.newProject
            ? { projectId: dialog.projectId, notes: dialog.notes, newProject: dialog.newProject }
            : recent
              ? { projectId: recent.project.id, taskId: recent.task.id }
              : undefined
        }
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
