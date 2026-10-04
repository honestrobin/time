// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, Dialog, DialogActions, DurationField, MoneyField, SelectField, TextAreaField, TextField, useConfirm, useToast } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { formatDate } from "../../lib/format";
import { usePermissions } from "../../lib/session";
import { accountQuery, byName, clientKeys, clientsQuery, projectKeys, taskKeys, tasksQuery } from "../projects/queries";
import { useAssignments, useInvalidateTime, type TimeEntry } from "./hooks";
import { ProjectTaskPicker } from "./ProjectTaskPicker";
import { ensureWork, NEW, type CatalogOps } from "./quickCreate";

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
  const assignmentsQuery = useAssignments();
  const assignments = assignmentsQuery.data ?? [];
  const refetchAssignments = assignmentsQuery.refetch;
  const [projectId, setProjectId] = useState<string | undefined>();
  const [taskId, setTaskId] = useState<string | undefined>();
  const [notes, setNotes] = useState("");
  const [duration, setDuration] = useState<number | null>(null);
  const [billable, setBillable] = useState(true);

  // Enter as you go: people who may create projects can add a missing project, client or task
  // here instead of setting them up first. Suggestions (undefined below) fill what's left empty.
  const perms = usePermissions();
  const qc = useQueryClient();
  const canCreate = !entry && perms.canManageProjects;
  const clientList = useQuery({ ...clientsQuery, enabled: open && canCreate });
  const taskList = useQuery({ ...tasksQuery, enabled: open && canCreate });
  const account = useQuery({ ...accountQuery, enabled: open && canCreate });
  const [newProject, setNewProject] = useState(false);
  const [projectName, setProjectName] = useState("");
  const [clientChoice, setClientChoice] = useState<string | undefined>();
  const [clientName, setClientName] = useState<string | undefined>();
  const [newTask, setNewTask] = useState(false);
  const [taskChoice, setTaskChoice] = useState<string | undefined>();
  const [taskName, setTaskName] = useState<string | undefined>();
  const [hourlyRate, setHourlyRate] = useState<number | null>(null);
  const [formError, setFormError] = useState<{ client?: string; task?: string }>({});

  // Projects may have changed since the list was loaded: a new one, or a manager added you.
  useEffect(() => {
    if (open) void refetchAssignments();
  }, [open, refetchAssignments]);

  useEffect(() => {
    if (!open) return;
    setProjectId(entry?.project.id ?? initial?.projectId);
    setTaskId(entry?.task.id ?? initial?.taskId);
    setNotes(entry?.notes ?? "");
    setDuration(entry ? entry.duration_seconds : null);
    setBillable(entry?.billable ?? true);
    setNewProject(false);
    setProjectName("");
    setClientChoice(undefined);
    setClientName(undefined);
    setNewTask(false);
    setTaskChoice(undefined);
    setTaskName(undefined);
    setHourlyRate(null);
    setFormError({});
  }, [open, entry, initial?.projectId, initial?.taskId]);

  const clients = (clientList.data?.data ?? []).filter((c) => c.is_active).sort(byName);
  const tasks = (taskList.data?.data ?? []).filter((x) => x.is_active).sort(byName);
  const project = assignments.find((a) => a.project_id === projectId);
  // With nothing to track to yet, the dialog starts with a new project.
  const creatingProject = canCreate && (newProject || (!assignmentsQuery.isFetching && assignments.length === 0));
  const canAddTask = canCreate && !!project?.is_manager;
  const creatingTask = !creatingProject && canAddTask && (newTask || project?.tasks.length === 0);
  const client = clientChoice ?? (clientList.isSuccess && clients.length === 0 ? NEW : undefined);
  const clientNameValue = clientName ?? (clients.length === 0 ? (account.data?.name ?? "") : "");
  const projectTask = taskChoice ?? tasks.find((x) => x.is_default)?.id ?? (taskList.isSuccess && tasks.length === 0 ? NEW : undefined);
  const taskNameValue = taskName ?? (tasks.length === 0 ? t("time.defaultTaskName") : "");
  // A new project is billed in its client's currency; a new client gets the account's.
  const rateCurrency =
    (client && client !== NEW ? clientList.data?.data.find((c) => c.id === client)?.currency : undefined) ?? account.data?.default_currency ?? "";

  const catalog: CatalogOps = {
    clients,
    tasks,
    createClient: (name) => unwrap(api.POST("/api/v1/clients", { body: { name, currency: account.data?.default_currency } })),
    createTask: (name, isDefault) => unwrap(api.POST("/api/v1/tasks", { body: { name, is_default: isDefault } })),
    createProject: (clientId, name, taskIds, rate) =>
      unwrap(api.POST("/api/v1/projects", { body: { client_id: clientId, name, task_ids: taskIds, hourly_rate: rate ?? undefined } })),
    addTaskToProject: (id, taskId) => unwrap(api.POST("/api/v1/projects/{id}/tasks", { params: { path: { id } }, body: { task_id: taskId } })),
  };

  /** The project and task to save to, creating whatever was entered as new. */
  const prepare = async (): Promise<{ projectId: string | undefined; taskId: string | undefined }> => {
    if (!creatingProject && !creatingTask) return { projectId, taskId };
    const work = await ensureWork(
      {
        projectId: creatingProject ? null : (projectId ?? null),
        projectName,
        clientId: client && client !== NEW ? client : null,
        clientName: clientNameValue,
        taskId: creatingProject && projectTask && projectTask !== NEW ? projectTask : null,
        taskName: creatingProject && projectTask !== NEW ? "" : taskNameValue,
        projectTasks: project?.tasks,
        hourlyRate: creatingProject ? hourlyRate : null,
      },
      catalog,
    );
    // What exists now stays chosen, so trying again after a later failure creates nothing twice.
    await refetchAssignments();
    setNewProject(false);
    setNewTask(false);
    setProjectId(work.projectId);
    setTaskId(work.taskId);
    void qc.invalidateQueries({ queryKey: projectKeys.all });
    void qc.invalidateQueries({ queryKey: clientKeys.all });
    void qc.invalidateQueries({ queryKey: taskKeys.all });
    return work;
  };

  const save = useMutation({
    mutationFn: async () => {
      const { projectId, taskId } = await prepare();
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
  // Tracked time is money and there is no undo, so deleting an entry asks first.
  const [confirmDelete, askDelete] = useConfirm({
    title: t("time.deleteEntryTitle"),
    body: t("time.deleteEntryBody"),
    confirmLabel: t("time.deleteEntry"),
    danger: true,
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
    if (creatingProject && !client) return setFormError({ client: t("time.chooseClientError") });
    if (creatingProject && !projectTask) return setFormError({ task: t("time.chooseTaskError") });
    setFormError({});
    save.mutate();
  };
  const ready = creatingProject || (creatingTask ? !!projectId : !!projectId && !!taskId);
  const creating = creatingProject || creatingTask;

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={entry ? t("time.editEntry") : t("time.newEntry")}
      description={formatDate(date, "long")}
    >
      {assignments.length === 0 && assignmentsQuery.isFetching ? (
        <p className="muted">{t("app.loading")}</p>
      ) : assignments.length === 0 && !canCreate ? (
        <p className="notice">{t("time.noAssignments")}</p>
      ) : (
        <form className="stack" onSubmit={submit}>
          {assignments.length === 0 && <p className="muted">{t("time.quickLead")}</p>}
          {err && (creating || !Object.keys(err.fields).length) && (
            <p className="notice notice-error" role="alert">
              {[err.message, ...Object.values(err.fields)].filter((m, i, all) => m && all.indexOf(m) === i).join(" ")}
            </p>
          )}
          <ProjectTaskPicker
            assignments={assignments}
            projectId={creatingProject ? NEW : projectId}
            taskId={creatingTask ? NEW : taskId}
            onChange={(p, t2) => {
              setNewProject(false);
              setNewTask(false);
              setProjectId(p);
              setTaskId(t2);
            }}
            onTaskChange={(x) => {
              setNewTask(false);
              setTaskId(x);
            }}
            onNewProject={canCreate ? () => setNewProject(true) : undefined}
            onNewTask={canAddTask ? () => setNewTask(true) : undefined}
            hideTask={creatingProject}
            errors={creating ? undefined : err?.fields}
            autoFocus={!entry && !creatingProject}
          />
          {creatingProject && (
            <>
              <TextField label={t("time.projectName")} value={projectName} onChange={setProjectName} required autoFocus />
              <div className="form-grid">
                <SelectField
                  label={t("time.client")}
                  value={client}
                  onChange={(v) => {
                    setClientChoice(v);
                    setFormError({});
                  }}
                  placeholder={t("time.chooseClient")}
                  options={[...clients.map((c) => ({ value: c.id, label: c.name })), { value: NEW, label: t("time.newClient") }]}
                  error={formError.client}
                />
                <SelectField
                  label={t("time.task")}
                  value={projectTask}
                  onChange={(v) => {
                    setTaskChoice(v);
                    setFormError({});
                  }}
                  placeholder={t("time.chooseTask")}
                  options={[...tasks.map((x) => ({ value: x.id, label: x.name })), { value: NEW, label: t("time.newTask") }]}
                  error={formError.task}
                />
              </div>
              {client === NEW && (
                <TextField
                  label={t("time.clientName")}
                  value={clientNameValue}
                  onChange={setClientName}
                  hint={clients.length === 0 && clientName === undefined ? t("time.clientNameOwnHint") : undefined}
                  required
                />
              )}
              {projectTask === NEW && <TextField label={t("time.taskName")} value={taskNameValue} onChange={setTaskName} required />}
              {perms.canSeeRates && rateCurrency && (
                <MoneyField
                  label={t("time.hourlyRate")}
                  hint={t("time.hourlyRateHint")}
                  value={hourlyRate}
                  onChange={setHourlyRate}
                  currency={rateCurrency}
                />
              )}
            </>
          )}
          {creatingTask && <TextField label={t("time.taskName")} value={taskNameValue} onChange={setTaskName} required autoFocus={newTask} />}
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
          {confirmDelete}
          <DialogActions>
            {entry && !entry.is_locked && (
              <Button variant="danger" onClick={() => askDelete(() => remove.mutate())} busy={remove.isPending} style={{ marginRight: "auto" }}>
                {t("app.delete")}
              </Button>
            )}
            <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
            <Button type="submit" variant="primary" busy={save.isPending} disabled={!ready || (startsTimer && !isToday)}>
              {entry ? t("app.save") : startsTimer ? t("time.startTimer") : t("time.saveEntry")}
            </Button>
          </DialogActions>
        </form>
      )}
    </Dialog>
  );
}
