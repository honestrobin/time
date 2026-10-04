// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, ConfirmDialog, Dialog, DialogActions, EmptyState, LoadingRow, Menu, MoneyField, PageHeader, TextField, useToast } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { formatMoney } from "../../lib/format";
import { usePermissions } from "../../lib/session";
import { byName, taskKeys, tasksQuery, useAccountDefaults, type Task } from "../projects/queries";
import "../projects/catalog.css";

/** The account-wide task list that projects pick from. */
export function TasksPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const perms = usePermissions();
  const { currency } = useAccountDefaults();
  const q = useQuery(tasksQuery);
  const tasks = [...(q.data?.data ?? [])].sort((a, b) => Number(b.is_active) - Number(a.is_active) || byName(a, b));
  const canEdit = perms.canManageProjects;

  // null: closed; "new": creating; otherwise the task being edited.
  const [open, setOpen] = useState<Task | "new" | null>(null);
  const [deleting, setDeleting] = useState<Task | null>(null);

  const setActive = useMutation({
    mutationFn: (task: Task) => unwrap(api.PATCH("/api/v1/tasks/{id}", { params: { path: { id: task.id } }, body: { is_active: !task.is_active } })),
    onSuccess: (task) => {
      void qc.invalidateQueries({ queryKey: taskKeys.all });
      toast(task.is_active ? t("tasks.restored", { name: task.name }) : t("tasks.archived", { name: task.name }));
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  const remove = useMutation({
    mutationFn: (task: Task) => unwrap(api.DELETE("/api/v1/tasks/{id}", { params: { path: { id: task.id } } })),
    onSuccess: (_, task) => {
      setDeleting(null);
      void qc.invalidateQueries({ queryKey: taskKeys.all });
      toast(t("tasks.deleted", { name: task.name }));
    },
  });

  const cols = 5 + (perms.canSeeRates ? 1 : 0);

  return (
    <div className="page">
      <PageHeader
        title={t("tasks.title")}
        lead={t("tasks.lead")}
        actions={
          canEdit && (
            <Button variant="primary" onClick={() => setOpen("new")}>
              {t("tasks.newTask")}
            </Button>
          )
        }
      />

      {q.isError ? (
        <p className="notice notice-error" role="alert">
          {errorInfo(q.error).message}
        </p>
      ) : !q.isLoading && tasks.length === 0 ? (
        <EmptyState
          robin="notes"
          title={t("tasks.empty.title")}
          body={t("tasks.empty.body")}
          action={
            canEdit && (
              <Button variant="primary" onClick={() => setOpen("new")}>
                {t("tasks.empty.action")}
              </Button>
            )
          }
        />
      ) : (
        <table className="ledger">
          <thead>
            <tr>
              <th>{t("tasks.fields.name")}</th>
              <th>{t("tasks.list.isDefault")}</th>
              <th>{t("tasks.list.billable")}</th>
              {perms.canSeeRates && <th className="num">{t("tasks.fields.defaultRate")}</th>}
              <th className="col-narrow">{t("tasks.list.status")}</th>
              <th>
                <span className="sr-only">{t("tasks.list.actions")}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {q.isLoading && <LoadingRow colSpan={cols} />}
            {tasks.map((task) => (
              <tr key={task.id} className={["is-clickable", task.is_active ? "" : "is-archived"].join(" ")} onClick={() => canEdit && setOpen(task)}>
                <td>
                  {canEdit ? (
                    <button
                      type="button"
                      className="link-button"
                      onClick={(e) => {
                        e.stopPropagation();
                        setOpen(task);
                      }}
                    >
                      {task.name}
                    </button>
                  ) : (
                    <strong>{task.name}</strong>
                  )}
                </td>
                <td>{task.is_default ? t("tasks.list.addedToNew") : <span className="muted">{t("tasks.list.notAdded")}</span>}</td>
                <td>{task.default_billable ? t("tasks.list.billableYes") : <span className="muted">{t("tasks.list.billableNo")}</span>}</td>
                {perms.canSeeRates && <td className="num">{task.default_rate != null ? formatMoney(task.default_rate, currency) : ""}</td>}
                <td>{task.is_active ? t("tasks.list.active") : <span className="badge">{t("tasks.list.archivedBadge")}</span>}</td>
                <td className="actions" onClick={(e) => e.stopPropagation()}>
                  {canEdit && (
                    <Menu
                      trigger={
                        <Button size="sm" variant="ghost" aria-label={t("tasks.list.actionsFor", { name: task.name })}>
                          ⋯
                        </Button>
                      }
                      items={[
                        { label: t("tasks.edit"), onSelect: () => setOpen(task) },
                        { label: task.is_active ? t("tasks.archive") : t("tasks.restore"), onSelect: () => setActive.mutate(task) },
                        "separator",
                        {
                          label: t("tasks.delete"),
                          danger: true,
                          onSelect: () => {
                            remove.reset();
                            setDeleting(task);
                          },
                        },
                      ]}
                    />
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <TaskDialog task={open} currency={currency} onClose={() => setOpen(null)} />

      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(o) => !o && setDeleting(null)}
        title={t("tasks.deleteTitle", { name: deleting?.name ?? "" })}
        body={
          <div className="stack">
            <p>{t("tasks.deleteBody")}</p>
            {remove.error && (
              <p className="notice notice-error" role="alert">
                {errorInfo(remove.error).message}
              </p>
            )}
          </div>
        }
        confirmLabel={t("tasks.delete")}
        busy={remove.isPending}
        onConfirm={() => deleting && remove.mutate(deleting)}
      />
    </div>
  );
}

interface TaskForm {
  name: string;
  is_default: boolean;
  default_billable: boolean;
  default_rate: number | null;
}

function TaskDialog({ task, currency, onClose }: { task: Task | "new" | null; currency: string; onClose: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const perms = usePermissions();
  const isNew = task === "new";
  const existing = task && task !== "new" ? task : null;
  const [form, setForm] = useState<TaskForm>({ name: "", is_default: true, default_billable: true, default_rate: null });

  const save = useMutation({
    mutationFn: (f: TaskForm) => {
      const body: Schemas["TaskInput"] = { name: f.name.trim(), is_default: f.is_default, default_billable: f.default_billable };
      if (perms.canSeeRates && (isNew ? f.default_rate !== null : f.default_rate !== (existing?.default_rate ?? null))) {
        // null asks the API to clear the rate.
        (body as Record<string, unknown>).default_rate = f.default_rate;
      }
      return isNew
        ? unwrap(api.POST("/api/v1/tasks", { body }))
        : unwrap(api.PATCH("/api/v1/tasks/{id}", { params: { path: { id: existing!.id } }, body }));
    },
    onSuccess: (saved) => {
      void qc.invalidateQueries({ queryKey: taskKeys.all });
      toast(isNew ? t("tasks.created", { name: saved.name }) : t("app.saved"));
      onClose();
    },
  });

  useEffect(() => {
    if (task === null) return;
    setForm(
      existing
        ? { name: existing.name, is_default: existing.is_default, default_billable: existing.default_billable, default_rate: existing.default_rate ?? null }
        : { name: "", is_default: true, default_billable: true, default_rate: null },
    );
    save.reset();
    // Only when the dialog opens or switches task.
  }, [task]); // eslint-disable-line react-hooks/exhaustive-deps

  const err = save.error ? errorInfo(save.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate(form);
  };

  return (
    <Dialog open={task !== null} onOpenChange={(o) => !o && onClose()} title={isNew ? t("tasks.newTask") : t("tasks.editTask")}>
      <form className="stack" onSubmit={submit} noValidate>
        <TextField
          label={t("tasks.fields.name")}
          hint={t("tasks.fields.nameHint")}
          value={form.name}
          onChange={(v) => setForm((f) => ({ ...f, name: v }))}
          error={err?.fields.name}
          required
          autoFocus
          autoComplete="off"
        />
        <Checkbox
          checked={form.is_default}
          onChange={(v) => setForm((f) => ({ ...f, is_default: v }))}
          label={t("tasks.fields.isDefault")}
          hint={t("tasks.fields.isDefaultHint")}
        />
        <Checkbox
          checked={form.default_billable}
          onChange={(v) => setForm((f) => ({ ...f, default_billable: v }))}
          label={t("tasks.fields.defaultBillable")}
          hint={t("tasks.fields.defaultBillableHint")}
        />
        {perms.canSeeRates && (
          <div className="form-grid">
            <MoneyField
              label={t("tasks.fields.defaultRate")}
              hint={t("tasks.fields.defaultRateHint")}
              value={form.default_rate}
              onChange={(v) => setForm((f) => ({ ...f, default_rate: v }))}
              currency={currency}
              error={err?.fields.default_rate}
            />
          </div>
        )}
        {err && !Object.keys(err.fields).length && (
          <p className="notice notice-error" role="alert">
            {err.message}
          </p>
        )}
        <DialogActions>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button type="submit" variant="primary" busy={save.isPending}>
            {isNew ? t("tasks.create") : t("app.save")}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
