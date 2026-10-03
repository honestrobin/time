// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, Dialog, DialogActions, DurationField, TextAreaField, useToast } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { formatDate } from "../../lib/format";
import { useAssignments, useInvalidateTime, type TimeEntry } from "./hooks";
import { ProjectTaskPicker } from "./ProjectTaskPicker";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  date: string;
  isToday: boolean;
  /** Present when editing. */
  entry?: TimeEntry | null;
  durationStyle: "hm" | "decimal";
  /** Pre-select a project and task (e.g. from the most recent entry). */
  initial?: { projectId?: string; taskId?: string };
}

export function EntryDialog({ open, onOpenChange, date, isToday, entry, durationStyle, initial }: Props) {
  const { t } = useTranslation();
  const toast = useToast();
  const invalidate = useInvalidateTime();
  const assignments = useAssignments().data ?? [];
  const [projectId, setProjectId] = useState<string | undefined>();
  const [taskId, setTaskId] = useState<string | undefined>();
  const [notes, setNotes] = useState("");
  const [duration, setDuration] = useState<number | null>(null);
  const [billable, setBillable] = useState(true);

  useEffect(() => {
    if (!open) return;
    setProjectId(entry?.project.id ?? initial?.projectId);
    setTaskId(entry?.task.id ?? initial?.taskId);
    setNotes(entry?.notes ?? "");
    setDuration(entry ? entry.duration_seconds : null);
    setBillable(entry?.billable ?? true);
  }, [open, entry, initial?.projectId, initial?.taskId]);

  const save = useMutation({
    mutationFn: async () => {
      if (entry) {
        const body: Record<string, unknown> = { project_id: projectId, task_id: taskId, notes, billable };
        if (!entry.is_running && duration !== null) body.duration_seconds = duration;
        return unwrap(api.PATCH("/api/v1/time_entries/{id}", { params: { path: { id: entry.id } }, body: body as never }));
      }
      return unwrap(
        api.POST("/api/v1/time_entries", {
          body: {
            project_id: projectId,
            task_id: taskId,
            spent_date: date,
            notes,
            duration_seconds: duration ?? undefined,
          },
        }),
      );
    },
    onSuccess: async (saved) => {
      await invalidate();
      toast(entry ? t("time.entrySaved") : saved.is_running ? t("time.timerStarted") : t("time.entryAdded"));
      onOpenChange(false);
    },
  });
  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/time_entries/{id}", { params: { path: { id: entry!.id } } })),
    onSuccess: async () => {
      await invalidate();
      toast(t("time.entryDeleted"));
      onOpenChange(false);
    },
  });

  const err = save.error ? errorInfo(save.error) : null;
  const startsTimer = !entry && duration === null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate();
  };
  const project = assignments.find((a) => a.project_id === projectId);

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={entry ? t("time.editEntry") : t("time.newEntry")}
      description={formatDate(date, "long")}
    >
      {assignments.length === 0 ? (
        <p className="notice">{t("time.noAssignments")}</p>
      ) : (
        <form className="stack" onSubmit={submit}>
          {err && !Object.keys(err.fields).length && (
            <p className="notice notice-error" role="alert">
              {err.message}
            </p>
          )}
          <ProjectTaskPicker
            assignments={assignments}
            projectId={projectId}
            taskId={taskId}
            onChange={(p, t2) => {
              setProjectId(p);
              setTaskId(t2);
            }}
            onTaskChange={setTaskId}
            errors={err?.fields}
            autoFocus={!entry}
          />
          <TextAreaField label={t("time.notes")} value={notes} onChange={setNotes} rows={3} error={err?.fields.notes} />
          <div className="form-grid">
            <DurationField
              label={t("time.duration")}
              hint={entry?.is_running ? t("time.durationRunningHint") : isToday && !entry ? t("time.durationHint") : t("time.durationHintPast")}
              value={duration}
              onChange={setDuration}
              style={durationStyle}
              disabled={entry?.is_running}
              error={err?.fields.duration_seconds}
              placeholder={isToday && !entry ? "0:00" : undefined}
            />
            {project?.is_billable && (
              <div style={{ alignSelf: "end", paddingBottom: 8 }}>
                <Checkbox checked={billable} onChange={setBillable} label={t("time.billable")} />
              </div>
            )}
          </div>
          <DialogActions>
            {entry && !entry.is_locked && (
              <Button variant="danger" onClick={() => remove.mutate()} busy={remove.isPending} style={{ marginRight: "auto" }}>
                {t("app.delete")}
              </Button>
            )}
            <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
            <Button type="submit" variant="primary" busy={save.isPending} disabled={!projectId || !taskId || (startsTimer && !isToday)}>
              {entry ? t("app.save") : startsTimer ? t("time.startTimer") : t("time.saveEntry")}
            </Button>
          </DialogActions>
        </form>
      )}
    </Dialog>
  );
}
