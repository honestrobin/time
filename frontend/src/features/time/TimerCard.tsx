// SPDX-License-Identifier: AGPL-3.0-only
import * as RSelect from "@radix-ui/react-select";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { NONE } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { formatClock, formatDuration, formatMomentTime } from "../../lib/format";
import { useHotkeys } from "../../lib/hotkeys";
import { usePermissions } from "../../lib/session";
import { liveSeconds, useAccountSettings, useAssignments, useInvalidateTime, useNow, type Assignment, type TimeEntry } from "./hooks";
import { NEW } from "./quickCreate";

interface Props {
  /** The running timer, if any. */
  running: TimeEntry | null;
  fetchedAt: number;
  /** The entry touched last: a new timer starts on its project. */
  recent: TimeEntry | undefined;
  /** Opens the running entry to change its note or project. */
  onEdit: (entry: TimeEntry) => void;
  /** Nothing to track to yet, or a new project: the entry dialog takes over, with the note typed so far. */
  onNeedDetails: (start: { notes: string; projectId?: string; newProject?: boolean }) => void;
}

/**
 * Today's timer. Stopped, it asks what you're working on: type a note, press Enter or N, and it
 * runs on the project you used last. Running, the number counts and the orange dot pulses.
 * Stopping is quiet. What was typed stays until the timer has started.
 */
export function TimerCard({ running, fetchedAt, recent, onEdit, onNeedDetails }: Props) {
  const { t } = useTranslation();
  const account = useAccountSettings();
  const assignments = useAssignments().data ?? [];
  const perms = usePermissions();
  const invalidate = useInvalidateTime();
  const qc = useQueryClient();
  const now = useNow(!!running);
  const [note, setNote] = useState("");
  const [choice, setChoice] = useState<string | undefined>();
  // A timer started elsewhere (the entry dialog, another tab, the extension) takes the note with it.
  useEffect(() => {
    if (running) {
      setNote("");
      setChoice(undefined);
    }
  }, [running?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const projectId = choice ?? (assignments.some((a) => a.project_id === recent?.project.id) ? recent?.project.id : assignments[0]?.project_id);
  const project = assignments.find((a) => a.project_id === projectId);
  // The task you used last on this project, or its first.
  const taskId =
    recent && recent.project.id === projectId && project?.tasks.some((x) => x.task_id === recent.task.id) ? recent.task.id : project?.tasks[0]?.task_id;

  const start = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/time_entries", {
          body: { project_id: projectId, task_id: taskId, spent_date: account.today, notes: note.trim() || undefined },
        }),
      ),
    onSuccess: async () => {
      setNote("");
      setChoice(undefined);
      await invalidate();
    },
    // The project may have changed elsewhere (archived, or you were taken off it): look again.
    onError: () => void qc.invalidateQueries({ queryKey: ["me", "assignments"] }),
  });
  const stop = useMutation({
    mutationFn: (id: string) => unwrap(api.POST("/api/v1/time_entries/{id}/stop", { params: { path: { id } } })),
    onSuccess: () => invalidate(),
  });

  const go = () => {
    if (start.isPending) return;
    if (!project || !taskId) return onNeedDetails({ notes: note, projectId });
    start.mutate();
  };
  useHotkeys({
    n: () => {
      if (!running) go();
    },
    s: () => {
      if (running && !stop.isPending) stop.mutate(running.id);
    },
  });

  if (running) {
    const seconds = liveSeconds(running, now, fetchedAt);
    const since = running.timer_started_at ? formatMomentTime(running.timer_started_at, account.timezone) : null;
    const clock = formatClock(seconds);
    return (
      <section className="timer-card is-running" aria-label={t("time.timer")}>
        <span className="live-dot" aria-hidden />
        <button type="button" className="timer-what" onClick={() => onEdit(running)} title={t("time.editRunning")}>
          <span className={running.notes ? "timer-note" : "timer-note is-empty"}>{running.notes || t("time.noNote")}</span>
          <span className="timer-meta">
            <span className="timer-chip">
              {running.client.name} · {running.project.name}
            </span>
            {since && <span>{t("time.since", { time: since })}</span>}
          </span>
        </button>
        <div className="timer-clock" role="timer" aria-label={t("time.elapsedIs", { time: formatDuration(seconds) })}>
          {clock.slice(0, -3)}
          <span className="timer-sec">{clock.slice(-3)}</span>
        </div>
        <button type="button" className="timer-button is-stop" onClick={() => stop.mutate(running.id)} disabled={stop.isPending}>
          <span className="stop-square" aria-hidden />
          {t("time.stop")}
          <kbd className="kbd" aria-hidden>
            S
          </kbd>
        </button>
        {stop.error && (
          <p className="timer-error" role="alert">
            {errorInfo(stop.error).message}
          </p>
        )}
      </section>
    );
  }

  const submit = (e: FormEvent) => {
    e.preventDefault();
    go();
  };
  return (
    <form className="timer-card" aria-label={t("time.timer")} onSubmit={submit}>
      <label className="timer-ask">
        <span className="timer-label">{t("time.whatAreYouWorkingOn")}</span>
        <input
          className="timer-input"
          value={note}
          onChange={(e) => {
            setNote(e.target.value);
            start.reset();
          }}
          placeholder={t("time.notePlaceholder")}
          aria-invalid={start.error ? true : undefined}
          aria-describedby={start.error ? "timer-error" : undefined}
        />
      </label>
      <ProjectChip
        assignments={assignments}
        value={project?.project_id}
        onChange={(v) => {
          // Radix also reports the empty value its hidden form field starts with: that's no choice.
          if (!v || v === NONE) return;
          start.reset();
          if (v === NEW) onNeedDetails({ notes: note, newProject: true });
          else setChoice(v);
        }}
        canCreate={perms.canManageProjects}
      />
      <button type="submit" className="timer-button" disabled={start.isPending}>
        <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden>
          <path d="M3 1.5v11l9.5-5.5z" fill="currentColor" />
        </svg>
        {t("time.start")}
        <kbd className="kbd" aria-hidden>
          N
        </kbd>
      </button>
      {start.error && (
        <p className="timer-error" id="timer-error" role="alert">
          {t("time.notStarted")} {errorInfo(start.error).message}
        </p>
      )}
    </form>
  );
}

/** The project a new timer runs on, as a chip: "Client · Project ▾". */
function ProjectChip({
  assignments,
  value,
  onChange,
  canCreate,
}: {
  assignments: Assignment[];
  value: string | undefined;
  onChange: (projectId: string) => void;
  canCreate: boolean;
}) {
  const { t } = useTranslation();
  const byClient = new Map<string, Assignment[]>();
  assignments.forEach((a) => byClient.set(a.client.name, [...(byClient.get(a.client.name) ?? []), a]));
  const chosen = assignments.find((a) => a.project_id === value);
  return (
    <RSelect.Root value={value ?? NONE} onValueChange={onChange}>
      <RSelect.Trigger className="project-chip" aria-label={t("time.project")}>
        <span className="project-chip-text">{chosen ? `${chosen.client.name} · ${chosen.project_name}` : t("time.chooseProject")}</span>
        <RSelect.Icon aria-hidden>▾</RSelect.Icon>
      </RSelect.Trigger>
      <RSelect.Portal>
        <RSelect.Content className="select-content" position="popper" sideOffset={4} align="end">
          <RSelect.Viewport>
            {[...byClient.entries()].map(([client, list]) => (
              <RSelect.Group key={client}>
                <RSelect.Label className="menu-label">{client}</RSelect.Label>
                {list.map((a) => (
                  <RSelect.Item key={a.project_id} value={a.project_id} className="select-item">
                    <RSelect.ItemText>{a.project_name}</RSelect.ItemText>
                  </RSelect.Item>
                ))}
              </RSelect.Group>
            ))}
            {canCreate && (
              <RSelect.Item value={NEW} className="select-item">
                <RSelect.ItemText>{t("time.newProject")}</RSelect.ItemText>
              </RSelect.Item>
            )}
          </RSelect.Viewport>
        </RSelect.Content>
      </RSelect.Portal>
    </RSelect.Root>
  );
}
