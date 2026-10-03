// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Dialog, DialogActions, DurationInput, useConfirm, useToast } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { formatDate, formatDuration, formatWeekday } from "../../lib/format";
import { useHotkeys } from "../../lib/hotkeys";
import { useMe } from "../../lib/session";
import { liveSeconds, useAccountSettings, useAssignments, useNow, type Week } from "./hooks";
import { ProjectTaskPicker } from "./ProjectTaskPicker";

type Row = Week["rows"][number];

export function WeekView({ date, personId }: { date: string; personId?: string }) {
  const { t } = useTranslation();
  const me = useMe();
  const qc = useQueryClient();
  const toast = useToast();
  const account = useAccountSettings();
  const style = account.durationStyle;
  const membershipId = personId ?? me.current_membership_id ?? undefined;
  const isSelf = membershipId === me.current_membership_id;
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

  useHotkeys({ n: () => setAddOpen(true) }, !week?.is_read_only);

  if (!week) return <p className="muted">{t("app.loading")}</p>;
  const readOnly = week.is_read_only;
  const today = account.today;
  const entrySeconds = (projectId: string, taskId: string, day: string) =>
    week.entries
      .filter((e) => e.project.id === projectId && e.task.id === taskId && e.spent_date === day)
      .reduce((s, e) => s + liveSeconds(e, now, q.dataUpdatedAt), 0);
  const dayTotal = (day: string) => week.entries.filter((e) => e.spent_date === day).reduce((s, e) => s + liveSeconds(e, now, q.dataUpdatedAt), 0);
  const total = week.entries.reduce((s, e) => s + liveSeconds(e, now, q.dataUpdatedAt), 0);
  const sub = week.submission;

  return (
    <>
      {sub && (
        <p className={sub.state === "rejected" ? "notice notice-warn" : sub.state === "approved" ? "notice notice-ok" : "notice"} style={{ marginBottom: 16 }}>
          {t(`time.submission.${sub.state}`, {
            date: formatDate((sub.decided_at ?? sub.submitted_at).slice(0, 10)),
            name: sub.decided_by?.name ?? "",
            comment: sub.comment ?? "",
          })}
        </p>
      )}
      {error && (
        <p className="notice notice-error" role="alert" style={{ marginBottom: 16 }}>
          {error}
        </p>
      )}
      <table className="ledger week-grid">
        <thead>
          <tr>
            <th>{t("time.project")}</th>
            {week.days.map((d) => (
              <th key={d} className={d === today ? "day today" : "day"}>
                <Link to="/time/day/$date" params={{ date: d }} style={{ color: "inherit", textDecoration: "none" }}>
                  {formatWeekday(d)} {Number(d.slice(8))}
                </Link>
              </th>
            ))}
            <th className="num">{t("time.total")}</th>
            <th aria-label={t("time.removeRow")} />
          </tr>
        </thead>
        <tbody>
          {week.rows.map((row) => (
            <tr key={`${row.project.id}:${row.task.id}`}>
              <td className="row-label">
                <strong>
                  {row.project.code ? `[${row.project.code}] ` : ""}
                  {row.project.name}
                </strong>
                <span>
                  {row.client.name}, {row.task.name}
                </span>
              </td>
              {week.days.map((d, i) => {
                const count = row.entry_counts[i];
                const editable = !readOnly && !row.is_locked && count <= 1 && !week.entries.some((e) => e.is_running && e.spent_date === d && e.project.id === row.project.id && e.task.id === row.task.id);
                const seconds = entrySeconds(row.project.id, row.task.id, d);
                return (
                  <td key={d} className="cell">
                    {editable ? (
                      <DurationInput
                        value={seconds || null}
                        style={style}
                        aria-label={`${row.project.name}, ${row.task.name}, ${formatDate(d)}`}
                        onChange={(v) => {
                          const next = v ?? 0;
                          if (next !== seconds) setCell.mutate({ row, day: d, seconds: next });
                        }}
                      />
                    ) : (
                      <Link
                        to="/time/day/$date"
                        params={{ date: d }}
                        className="cell-static"
                        title={count > 1 ? t("time.multipleEntries", { count }) : undefined}
                      >
                        {seconds ? formatDuration(seconds, style) : ""}
                      </Link>
                    )}
                  </td>
                );
              })}
              <td className="num" style={{ fontWeight: 650 }}>
                {formatDuration(week.days.reduce((sum, d) => sum + entrySeconds(row.project.id, row.task.id, d), 0), style)}
              </td>
              <td className="num">
                {!readOnly && !row.is_locked && (
                  <Button size="sm" variant="ghost" aria-label={t("time.removeRow")} onClick={() => askRemove(() => removeRow.mutate(row))}>
                    ✕
                  </Button>
                )}
              </td>
            </tr>
          ))}
          {week.rows.length === 0 && (
            <tr>
              <td colSpan={10} className="ledger-empty">
                {t("time.nothingTrackedBody")}
              </td>
            </tr>
          )}
        </tbody>
        <tfoot>
          <tr>
            <td className="muted">
              {t("time.capacity", { tracked: formatDuration(total, style), capacity: formatDuration(week.capacity_seconds, style) })}
            </td>
            {week.days.map((d) => (
              <td key={d} className="num total">
                {formatDuration(dayTotal(d), style)}
              </td>
            ))}
            <td className="num total">{formatDuration(total, style)}</td>
            <td />
          </tr>
        </tfoot>
      </table>

      {!readOnly && (
        <div className="week-actions">
          <Button onClick={() => setAddOpen(true)}>{t("time.addRow")}</Button>
          <Button variant="ghost" onClick={() => copy.mutate()} busy={copy.isPending}>
            {t("time.copyRows")}
          </Button>
          <span className="spacer" />
          {isSelf && week.approvals_enabled && sub?.state !== "submitted" && sub?.state !== "approved" && (
            <Button variant="primary" onClick={() => submit.mutate()} busy={submit.isPending} disabled={week.entries.length === 0}>
              {t("time.submitWeek")}
            </Button>
          )}
        </div>
      )}

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
