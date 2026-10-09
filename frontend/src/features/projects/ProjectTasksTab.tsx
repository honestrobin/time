// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, SelectField, useToast } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { formatDuration, formatMoney, minorToInput } from "../../lib/format";
import { useFeature } from "../../lib/features";
import { usePermissions } from "../../lib/session";
import { CellDuration, CellMoney } from "./cells";
import { byName, tasksQuery, useAccountDefaults, useProjectCache, type Project, type ProjectTask } from "./queries";

type TaskPatch = { [K in keyof Schemas["ProjectTaskInput"]]?: Schemas["ProjectTaskInput"][K] | null };

/** Tasks on the project: which are billable, their rates and budgets. */
export function ProjectTasksTab({ project, onRatesChanged }: { project: Project; onRatesChanged: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const perms = usePermissions();
  // The task list is a switched-off feature unless the server says otherwise (lib/features).
  const taskList = useFeature("tasks");
  const cache = useProjectCache();
  const { durationStyle } = useAccountDefaults();
  const tasksQ = useQuery(tasksQuery);
  const allTasks = tasksQ.data?.data ?? [];
  const taskById = new Map(allTasks.map((x) => [x.id, x]));
  const currency = project.client.currency;
  const [adding, setAdding] = useState<string | undefined>();

  const showRate = project.is_billable && project.bill_by === "tasks" && perms.canSeeRates;
  const showHours = project.budget_by === "task";
  const showFees = project.budget_by === "task_fees" && perms.canSeeRates;
  const showBillable = project.is_billable;

  const update = useMutation({
    mutationFn: ({ pt, body }: { pt: ProjectTask; body: TaskPatch; rates?: boolean; message?: string }) =>
      unwrap(
        api.PATCH("/api/v1/projects/{id}/tasks/{projectTaskId}", {
          params: { path: { id: project.id, projectTaskId: pt.id } },
          body: body as Schemas["ProjectTaskInput"],
        }),
      ),
    onSuccess: (p, vars) => {
      cache(p);
      toast(vars.message ?? t("app.saved"));
      if (vars.rates) onRatesChanged();
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  const add = useMutation({
    mutationFn: (taskId: string) => unwrap(api.POST("/api/v1/projects/{id}/tasks", { params: { path: { id: project.id } }, body: { task_id: taskId } })),
    onSuccess: (p, taskId) => {
      cache(p);
      setAdding(undefined);
      toast(t("projects.tasks.added", { name: taskById.get(taskId)?.name ?? "" }));
    },
  });

  const remove = useMutation({
    mutationFn: (pt: ProjectTask) =>
      unwrap(api.DELETE("/api/v1/projects/{id}/tasks/{projectTaskId}", { params: { path: { id: project.id, projectTaskId: pt.id } } })),
    onSuccess: (p, pt) => {
      cache(p);
      toast(t("projects.tasks.removed", { name: pt.name }));
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  const onProject = new Set(project.tasks.map((x) => x.task_id));
  const addable = allTasks.filter((x) => x.is_active && !onProject.has(x.id)).sort(byName);
  const addErr = add.error ? errorInfo(add.error) : null;
  const submitAdd = (e: FormEvent) => {
    e.preventDefault();
    if (adding) add.mutate(adding);
  };

  const budgetTotal = project.tasks.reduce((sum, x) => sum + ((showFees ? x.budget_amount : x.budget_seconds) ?? 0), 0);
  const cols = 2 + (showBillable ? 1 : 0) + (showRate ? 1 : 0) + (showHours || showFees ? 1 : 0);

  return (
    <section>
      <p className="muted inline-note" style={{ marginBottom: 16 }}>
        {showRate ? t("projects.tasks.leadRates") : t("projects.tasks.lead")}
      </p>
      <table className="ledger">
        <thead>
          <tr>
            <th>{t("projects.tasks.task")}</th>
            {showBillable && <th className="col-narrow" style={{ textAlign: "center" }}>{t("projects.tasks.billable")}</th>}
            {showRate && <th className="num">{t("projects.tasks.hourlyRate")}</th>}
            {showHours && <th className="num">{t("projects.tasks.budgetHours")}</th>}
            {showFees && <th className="num">{t("projects.tasks.budgetFees")}</th>}
            <th>
              <span className="sr-only">{t("projects.actions")}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {project.tasks.length === 0 && (
            <tr>
              <td colSpan={cols} className="muted">
                {t("projects.tasks.empty")}
              </td>
            </tr>
          )}
          {project.tasks.map((pt) => {
            const task = taskById.get(pt.task_id);
            return (
              <tr key={pt.id} className={pt.is_active ? undefined : "is-archived"}>
                <td>
                  {pt.name}
                  {!pt.is_active && <span className="badge" style={{ marginLeft: 8 }}>{t("projects.tasks.inactive")}</span>}
                  {task && !task.is_active && <span className="badge" style={{ marginLeft: 8 }}>{t("projects.tasks.archivedTask")}</span>}
                </td>
                {showBillable && (
                  <td>
                    <Checkbox
                      checked={pt.billable}
                      onChange={(v) => update.mutate({ pt, body: { billable: v }, rates: true })}
                      label={<span className="sr-only">{t("projects.tasks.billableFor", { name: pt.name })}</span>}
                    />
                  </td>
                )}
                {showRate && (
                  <td className="num">
                    <CellMoney
                      label={t("projects.tasks.rateFor", { name: pt.name })}
                      value={pt.hourly_rate ?? null}
                      currency={currency}
                      placeholder={task?.default_rate != null ? minorToInput(task.default_rate, currency) : undefined}
                      disabled={!pt.billable}
                      onCommit={(v) => update.mutate({ pt, body: { hourly_rate: v }, rates: true })}
                    />
                  </td>
                )}
                {showHours && (
                  <td className="num">
                    <CellDuration
                      label={t("projects.tasks.budgetFor", { name: pt.name })}
                      value={pt.budget_seconds ?? null}
                      style={durationStyle}
                      onCommit={(v) => update.mutate({ pt, body: { budget_seconds: v } })}
                    />
                  </td>
                )}
                {showFees && (
                  <td className="num">
                    <CellMoney
                      label={t("projects.tasks.budgetFor", { name: pt.name })}
                      value={pt.budget_amount ?? null}
                      currency={currency}
                      onCommit={(v) => update.mutate({ pt, body: { budget_amount: v } })}
                    />
                  </td>
                )}
                <td className="actions">
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() =>
                      update.mutate({
                        pt,
                        body: { is_active: !pt.is_active },
                        message: pt.is_active ? t("projects.tasks.deactivated", { name: pt.name }) : t("projects.tasks.activated", { name: pt.name }),
                      })
                    }
                  >
                    {pt.is_active ? t("projects.tasks.deactivate") : t("projects.tasks.activate")}
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => remove.mutate(pt)} busy={remove.isPending && remove.variables?.id === pt.id}>
                    {t("projects.tasks.remove")}
                  </Button>
                </td>
              </tr>
            );
          })}
        </tbody>
        {(showHours || showFees) && project.tasks.length > 0 && (
          <tfoot>
            <tr>
              <td className="total">{t("projects.overview.total")}</td>
              {showBillable && <td className="total" />}
              {showRate && <td className="total" />}
              <td className="total num">{showFees ? formatMoney(budgetTotal, currency) : formatDuration(budgetTotal, durationStyle)}</td>
              <td className="total" />
            </tr>
          </tfoot>
        )}
      </table>

      {showRate && <p className="field-hint" style={{ marginTop: 8 }}>{t("projects.tasks.rateHint")}</p>}

      <form className="add-row" onSubmit={submitAdd}>
        {addable.length > 0 ? (
          <>
            <SelectField
              label={t("projects.tasks.addLabel")}
              value={adding}
              onChange={setAdding}
              options={addable.map((x) => ({ value: x.id, label: x.name }))}
              placeholder={t("projects.tasks.choose")}
              error={addErr?.fields.task_id ?? (addErr && !Object.keys(addErr.fields).length ? addErr.message : undefined)}
            />
            <Button type="submit" disabled={!adding} busy={add.isPending}>
              {t("projects.tasks.add")}
            </Button>
          </>
        ) : (
          !tasksQ.isLoading && (
            <p className="muted">
              {t("projects.tasks.allAdded")}{" "}
              {perms.canManageProjects && taskList && <Link to="/tasks">{t("projects.tasks.manageTasks")}</Link>}
            </p>
          )
        )}
      </form>
    </section>
  );
}
