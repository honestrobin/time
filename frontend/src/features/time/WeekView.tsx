// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Dialog, DialogActions, DurationInput, useConfirm, useToast } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { useFeature } from "../../lib/features";
import { formatDate, formatDayHeading, formatDuration, formatTimeOfDay, formatWeekday } from "../../lib/format";
import { useHotkeys } from "../../lib/hotkeys";
import { useMe } from "../../lib/session";
import { liveSeconds, useAccountSettings, useAssignments, useNow, type TimeEntry, type Week } from "./hooks";
import { ProjectTaskPicker } from "./ProjectTaskPicker";
import { fillPercents } from "./track";
import { WeekStrip } from "./WeekStrip";

type Row = Week["rows"][number];
export type WeekShape = "table" | "strip";

/**
 * Track, one week: a table of hours by project and day that saves as you type (the default), or
 * the same week as strips of paper. One day is chosen, as in the day view, and shaded.
 */
export function WeekView({
  date,
  selected,
  onSelect,
  shape,
  personId,
}: {
  date: string;
  selected: string;
  onSelect: (day: string) => void;
  shape: WeekShape;
  personId?: string;
}) {
  const { t } = useTranslation();
  const me = useMe();
  const qc = useQueryClient();
  const toast = useToast();
  const account = useAccountSettings();
  const style = account.durationStyle;
  const tasks = useFeature("tasks");
  const membershipId = personId ?? me.current_membership_id ?? undefined;
  const isSelf = membershipId === me.current_membership_id;
  // Submitting a week for approval is part of the approvals feature (lib/features).
  const approvals = useFeature("approvals");
  const key = ["week", date, membershipId];
  const q = useQuery({
    queryKey: key,
    queryFn: () => unwrap(api.GET("/api/v1/timesheets/week", { params: { query: { start: date, membership_id: membershipId } } })),
  });
  const week = q.data;
  const running = week?.entries.some((e) => e.is_running) ?? false;
  const now = useNow(running);
  const [error, setError] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  // What was typed into a cell that isn't a time, by cell. It stays in its cell until it's fixed.
  const [notTimes, setNotTimes] = useState<Record<string, string>>({});
  const heard = (cell: string) => (text: string | null) =>
    setNotTimes((was) => {
      if (text === null ? !(cell in was) : was[cell] === text) return was;
      const next = { ...was };
      if (text === null) delete next[cell];
      else next[cell] = text;
      return next;
    });

  const onWeek = (w: Week) => {
    qc.setQueryData(key, w);
    setError(null);
    void qc.invalidateQueries({ queryKey: ["time_entries"] });
    void qc.invalidateQueries({ queryKey: ["timer"] });
  };
  const onError = (e: unknown) => setError(errorInfo(e).message);

  const setCell = useMutation({
    mutationFn: (v: { row: Row; day: string; seconds: number }) =>
      unwrap(
        api.PUT("/api/v1/timesheets/week/cell", {
          body: { membership_id: membershipId, project_id: v.row.project.id, task_id: v.row.task.id, spent_date: v.day, duration_seconds: v.seconds },
        }),
      ),
    onSuccess: onWeek,
    onError: (e) => {
      onError(e);
      void q.refetch();
    },
  });
  const copy = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/timesheets/week/copy_previous", { body: { start: date, membership_id: membershipId } })),
    onSuccess: (w) => {
      onWeek(w);
      toast(t("time.rowsCopied"));
    },
    onError,
  });
  const removeRow = useMutation({
    mutationFn: (row: Row) =>
      unwrap(
        api.DELETE("/api/v1/timesheets/week/rows", {
          params: { query: { start: date, project_id: row.project.id, task_id: row.task.id, membership_id: membershipId } },
        }),
      ),
    onSuccess: onWeek,
    onError,
  });
  const submit = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/timesheets/week/submit", { body: { start: date } })),
    onSuccess: (w) => {
      onWeek(w);
      toast(t("time.weekSubmitted"));
    },
    onError,
  });
  const [confirmRemove, askRemove] = useConfirm({ title: t("time.removeRow"), body: t("time.removeRowConfirm"), confirmLabel: t("time.removeRow") });

  useHotkeys({ n: () => setAddOpen(true) }, !week?.is_read_only && shape === "table");

  if (!week) return <p className="muted">{t("app.loading")}</p>;
  const readOnly = week.is_read_only;
  const seconds = (e: TimeEntry) => liveSeconds(e, now, q.dataUpdatedAt);
  const sum = (list: TimeEntry[]) => list.reduce((s, e) => s + seconds(e), 0);
  const inRow = (row: Row) => week.entries.filter((e) => e.project.id === row.project.id && e.task.id === row.task.id);
  const dayTotals = week.days.map((d) => sum(week.entries.filter((e) => e.spent_date === d)));
  const fills = fillPercents(dayTotals);
  const total = sum(week.entries);
  const sub = week.submission;
  const notATime = Object.values(notTimes)[0];
  const shade = (d: string) => (d === selected ? "is-selected" : undefined);
  const hint =
    shape === "strip"
      ? [t("time.stripPick"), week.entries.some((e) => !e.start_time) ? t("time.stripUntimed", { time: formatTimeOfDay("08:00") }) : ""].join(" ").trim()
      : notATime !== undefined
        ? t("time.notATime", { text: notATime })
        : readOnly
          ? ""
          : t("time.typeIntoADay");

  return (
    <>
      {sub && (
        <p className={sub.state === "rejected" ? "notice notice-warn" : sub.state === "approved" ? "notice notice-ok" : "notice"}>
          {t(`time.submission.${sub.state}`, {
            date: formatDate((sub.decided_at ?? sub.submitted_at).slice(0, 10)),
            name: sub.decided_by?.name ?? "",
            comment: sub.comment ?? "",
          })}
        </p>
      )}
      {error && (
        <p className="notice notice-error" role="alert">
          {error}
        </p>
      )}
      {shape === "strip" && <WeekStrip week={week} selected={selected} onSelect={onSelect} seconds={seconds} />}
      {/* Hidden, not removed, while the strips show: a typo waits in its cell until it's fixed. */}
      <div className="week-card" hidden={shape === "strip"}>
        <div className="table-scroll">
          <table className="week-table">
            <thead>
              <tr>
                <th scope="col" className="week-label">
                  {t("time.clientProject")}
                </th>
                {week.days.map((d) => (
                  <th scope="col" key={d} className={shade(d)}>
                    <button type="button" className="week-pick" aria-pressed={d === selected} aria-label={formatDayHeading(d)} onClick={() => onSelect(d)}>
                      <span className="d-name">{formatWeekday(d)}</span>
                      <span className="d-num">{Number(d.slice(8))}</span>
                    </button>
                  </th>
                ))}
                <th scope="col" className="week-sum">
                  {t("time.total")}
                </th>
                {!readOnly && <th className="week-remove" aria-label={t("time.removeRow")} />}
              </tr>
            </thead>
            <tbody>
              {week.rows.map((row) => {
                const rowKey = `${row.project.id}:${row.task.id}`;
                const entries = inRow(row);
                return (
                  <tr key={rowKey}>
                    <th scope="row" className="week-label">
                      <strong>
                        {row.project.code ? `[${row.project.code}] ` : ""}
                        {row.project.name}
                      </strong>
                      <span>
                        {row.client.name}
                        {tasks ? ` · ${row.task.name}` : ""}
                      </span>
                    </th>
                    {week.days.map((d, i) => {
                      const here = entries.filter((e) => e.spent_date === d);
                      const time = sum(here);
                      const isRunning = here.some((e) => e.is_running);
                      const count = row.entry_counts[i];
                      const editable = !readOnly && !row.is_locked && count <= 1 && !isRunning;
                      const cell = `${rowKey}:${d}`;
                      return (
                        <td key={d} className={shade(d)}>
                          {editable ? (
                            <DurationInput
                              className="week-cell"
                              value={time || null}
                              style={style}
                              placeholder="–"
                              aria-label={`${row.project.name}, ${row.task.name}, ${formatDate(d)}`}
                              onInvalid={heard(cell)}
                              onChange={(v) => {
                                const next = v ?? 0;
                                if (next !== time) setCell.mutate({ row, day: d, seconds: next });
                              }}
                            />
                          ) : time ? (
                            <Link
                              to="/day/$date"
                              params={{ date: d }}
                              className={isRunning ? "week-cell-static is-running" : "week-cell-static"}
                              aria-label={isRunning ? t("time.runningHere", { time: formatDuration(time, style) }) : undefined}
                              title={isRunning ? undefined : count > 1 ? t("time.multipleEntries", { count }) : undefined}
                            >
                              {isRunning && <span className="live-dot small" aria-hidden="true" />}
                              {formatDuration(time, style)}
                            </Link>
                          ) : null}
                        </td>
                      );
                    })}
                    <td className="week-sum">{formatDuration(sum(entries), style)}</td>
                    {!readOnly && (
                      <td className="week-remove">
                        {!row.is_locked && (
                          <button
                            type="button"
                            className="week-remove-button"
                            aria-label={`${t("time.removeRow")}: ${row.project.name}`}
                            onClick={() => askRemove(() => removeRow.mutate(row))}
                          >
                            ✕
                          </button>
                        )}
                      </td>
                    )}
                  </tr>
                );
              })}
              {week.rows.length === 0 && (
                <tr>
                  <td colSpan={readOnly ? 9 : 10} className="ledger-empty">
                    {t("time.nothingTrackedBody")}
                  </td>
                </tr>
              )}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row" className="week-label">
                  {t("time.thisWeek")}
                </th>
                {week.days.map((d, i) => (
                  <td key={d} className={shade(d)}>
                    <span className={dayTotals[i] ? "week-day-total" : "week-day-total is-zero"}>{dayTotals[i] ? formatDuration(dayTotals[i], style) : "–"}</span>
                    <span className="d-fill" aria-hidden="true">
                      <span style={{ width: `${fills[i]}%` }} />
                    </span>
                  </td>
                ))}
                <td className="week-sum week-total">{formatDuration(total, style)}</td>
                {!readOnly && <td />}
              </tr>
            </tfoot>
          </table>
        </div>
      </div>

      <div className="week-foot">
        {shape === "table" && !readOnly && (
          <>
            <button type="button" className="week-add-row" onClick={() => setAddOpen(true)}>
              <span aria-hidden="true">+ </span>
              {t("time.addRow")}
            </button>
            <Button variant="ghost" onClick={() => copy.mutate()} busy={copy.isPending}>
              {t("time.copyRows")}
            </Button>
          </>
        )}
        <p className={notATime !== undefined && shape === "table" ? "week-hint is-error" : "week-hint"} aria-live="polite">
          {hint}
        </p>
        {!readOnly && isSelf && approvals && week.approvals_enabled && sub?.state !== "submitted" && sub?.state !== "approved" && (
          <Button variant="primary" onClick={() => submit.mutate()} busy={submit.isPending} disabled={week.entries.length === 0}>
            {t("time.submitWeek")}
          </Button>
        )}
      </div>

      <AddRowDialog open={addOpen} onOpenChange={setAddOpen} start={date} membershipId={membershipId} onAdded={onWeek} />
      {confirmRemove}
    </>
  );
}

function AddRowDialog({
  open,
  onOpenChange,
  start,
  membershipId,
  onAdded,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  start: string;
  membershipId?: string;
  onAdded: (w: Week) => void;
}) {
  const { t } = useTranslation();
  const assignments = useAssignments().data ?? [];
  const [projectId, setProjectId] = useState<string>();
  const [taskId, setTaskId] = useState<string>();
  const add = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/timesheets/week/rows", { body: { start, membership_id: membershipId, project_id: projectId!, task_id: taskId! } })),
    onSuccess: (w) => {
      onAdded(w);
      onOpenChange(false);
    },
  });
  const err = add.error ? errorInfo(add.error) : null;
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={t("time.addRow")}>
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          add.mutate();
        }}
      >
        {err && <p className="notice notice-error">{err.message}</p>}
        <ProjectTaskPicker
          assignments={assignments}
          projectId={projectId}
          taskId={taskId}
          onChange={(p, t2) => {
            setProjectId(p);
            setTaskId(t2);
          }}
          onTaskChange={setTaskId}
          autoFocus
        />
        <DialogActions>
          <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
          <Button type="submit" variant="primary" disabled={!projectId || !taskId} busy={add.isPending}>
            {t("time.addRow")}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
