// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, PageHeader, useToast } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { useFeature } from "../../lib/features";
import { useMe, usePermissions } from "../../lib/session";
import { emptyProjectForm, formToCreateBody, ProjectFields, validateProjectForm, type ProjectFormState } from "./ProjectForm";
import { QuickClientDialog } from "./QuickClientDialog";
import { activePeopleQuery, byName, clientsQuery, projectKeys, tasksQuery, useAccountDefaults } from "./queries";
import "./catalog.css";

export function ProjectNewPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const perms = usePermissions();
  const taskList = useFeature("tasks");
  const me = useMe();
  const defaults = useAccountDefaults();

  const clientsQ = useQuery(clientsQuery);
  const tasksQ = useQuery(tasksQuery);
  const peopleQ = useQuery({ ...activePeopleQuery, enabled: perms.isManagerOrAdmin });

  const clients = useMemo(() => (clientsQ.data?.data ?? []).filter((c) => c.is_active).sort(byName), [clientsQ.data]);
  const tasks = useMemo(() => (tasksQ.data?.data ?? []).filter((x) => x.is_active).sort(byName), [tasksQ.data]);
  const people = useMemo(
    () => (peopleQ.data?.data ?? []).filter((p) => p.is_active && p.id !== me.current_membership_id).sort(byName),
    [peopleQ.data, me.current_membership_id],
  );

  const [form, setForm] = useState<ProjectFormState>(() => emptyProjectForm());
  const [taskIds, setTaskIds] = useState<Set<string> | null>(null);
  const [memberIds, setMemberIds] = useState<Set<string>>(new Set());
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});
  const [quickClient, setQuickClient] = useState(false);

  // Tick the account's default tasks once the list arrives.
  useEffect(() => {
    if (taskIds === null && tasksQ.data) setTaskIds(new Set(tasks.filter((x) => x.is_default).map((x) => x.id)));
  }, [tasksQ.data, tasks, taskIds]);

  // With a single client there is nothing to choose.
  useEffect(() => {
    if (!form.client_id && clients.length === 1) setForm((f) => ({ ...f, client_id: clients[0].id }));
  }, [clients, form.client_id]);

  const create = useMutation({
    mutationFn: () => {
      const auto = people.filter((p) => p.has_access_to_all_future_projects).map((p) => p.id);
      const body = {
        ...formToCreateBody(form, perms.canSeeRates),
        task_ids: taskIds ? [...taskIds] : undefined,
        membership_ids: [...new Set([...memberIds, ...auto])],
      };
      return unwrap(api.POST("/api/v1/projects", { body }));
    },
    onSuccess: (p) => {
      qc.setQueryData(projectKeys.detail(p.id), p);
      void qc.invalidateQueries({ queryKey: ["projects", "list"] });
      toast(t("projects.created"));
      void navigate({ to: "/projects/$projectId", params: { projectId: p.id } });
    },
  });

  const err = create.error ? errorInfo(create.error) : null;
  const errors = { ...(err?.fields ?? {}), ...localErrors };
  const currency = clients.find((c) => c.id === form.client_id)?.currency ?? defaults.currency;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const local = validateProjectForm(form, t);
    setLocalErrors(local);
    if (Object.keys(local).length) return;
    create.mutate();
  };

  const toggle = (set: Set<string>, id: string, on: boolean) => {
    const next = new Set(set);
    if (on) next.add(id);
    else next.delete(id);
    return next;
  };

  if (!perms.canManageProjects) {
    return (
      <div className="page page-narrow">
        <PageHeader title={t("projects.newProject")} />
        <p className="notice">{t("projects.noPermission")}</p>
      </div>
    );
  }

  return (
    <div className="page page-narrow">
      <PageHeader title={t("projects.newTitle")} lead={t("projects.newLead")} />
      <form onSubmit={submit} noValidate>
        <ProjectFields
          form={form}
          onChange={(patch) => setForm((f) => ({ ...f, ...patch }))}
          errors={errors}
          clients={clients}
          currency={currency}
          durationStyle={defaults.durationStyle}
          mode="create"
          onNewClient={() => setQuickClient(true)}
        />

        <section className="form-section">
          <h2>{t("projects.form.tasks")}</h2>
          <p className="muted lead">{t("projects.form.tasksLead")}</p>
          {tasksQ.isLoading ? (
            <p className="muted">{t("app.loading")}</p>
          ) : tasks.length === 0 ? (
            <p className="notice">
              {t("projects.form.noTasks")} {taskList && <Link to="/tasks">{t("projects.form.openTasks")}</Link>}
            </p>
          ) : (
            <>
              <div className="check-list-tools">
                <Button size="sm" variant="ghost" onClick={() => setTaskIds(new Set(tasks.map((x) => x.id)))}>
                  {t("projects.form.selectAll")}
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setTaskIds(new Set())}>
                  {t("projects.form.selectNone")}
                </Button>
              </div>
              <div className="check-list">
                {tasks.map((task) => (
                  <Checkbox
                    key={task.id}
                    checked={taskIds?.has(task.id) ?? false}
                    onChange={(on) => setTaskIds((s) => toggle(s ?? new Set(), task.id, on))}
                    label={task.name}
                    hint={task.default_billable ? undefined : t("projects.form.nonBillableTask")}
                  />
                ))}
              </div>
            </>
          )}
          {errors.task_ids && <p className="field-error">{errors.task_ids}</p>}
        </section>

        <section className="form-section">
          <h2>{t("projects.form.people")}</h2>
          <p className="muted lead">{t("projects.form.peopleLead")}</p>
          {peopleQ.isLoading ? (
            <p className="muted">{t("app.loading")}</p>
          ) : people.length === 0 ? (
            <p className="muted">{t("projects.form.noPeople")}</p>
          ) : (
            <div className="check-list">
              {people.map((p) =>
                p.has_access_to_all_future_projects ? (
                  <Checkbox key={p.id} checked disabled onChange={() => {}} label={p.name} hint={t("projects.form.allFutureProjects")} />
                ) : (
                  <Checkbox key={p.id} checked={memberIds.has(p.id)} onChange={(on) => setMemberIds((s) => toggle(s, p.id, on))} label={p.name} />
                ),
              )}
            </div>
          )}
          {errors.membership_ids && <p className="field-error">{errors.membership_ids}</p>}
        </section>

        <div className="form-actions">
          <Button type="submit" variant="primary" busy={create.isPending}>
            {t("projects.createProject")}
          </Button>
          <Link to="/projects" className="btn btn-secondary">
            {t("app.cancel")}
          </Link>
          {(err && !Object.keys(err.fields).length) || Object.keys(errors).length ? (
            <span className="field-error" role="alert">
              {err && !Object.keys(err.fields).length ? err.message : t("projects.form.fixErrors")}
            </span>
          ) : null}
        </div>
      </form>

      <QuickClientDialog open={quickClient} onOpenChange={setQuickClient} onCreated={(c) => setForm((f) => ({ ...f, client_id: c.id }))} />
    </div>
  );
}
