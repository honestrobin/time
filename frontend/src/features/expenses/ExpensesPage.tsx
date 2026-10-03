// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent, type MouseEvent } from "react";
import { useTranslation } from "react-i18next";
import {
  Button,
  Checkbox,
  ConfirmDialog,
  Dialog,
  DialogActions,
  EmptyState,
  Field,
  Kbd,
  LoadingRow,
  MoneyField,
  PageHeader,
  SelectField,
  TextAreaField,
  TextField,
  useToast,
} from "../../design";
import { api, ApiError, authHeaders, errorInfo, type Schemas, unwrap } from "../../lib/api";
import { addDays } from "../../lib/dates";
import { formatDate, formatMoney } from "../../lib/format";
import { useHotkeys } from "../../lib/hotkeys";
import { useMe, usePermissions } from "../../lib/session";
import { StateBadge } from "../time/DayView";
import { useAccountSettings, useAssignments } from "../time/hooks";
import "./expenses.css";

type Expense = Schemas["ExpenseView"];

/* ---- Month arithmetic on "YYYY-MM" ------------------------------------------------------- */

function shiftMonth(month: string, delta: number): string {
  const [y, m] = month.split("-").map(Number);
  const d = new Date(Date.UTC(y, m - 1 + delta, 1));
  return d.toISOString().slice(0, 7);
}

function monthRange(month: string): { from: string; to: string } {
  return { from: `${month}-01`, to: addDays(`${shiftMonth(month, 1)}-01`, -1) };
}

function formatMonth(month: string): string {
  return new Intl.DateTimeFormat(navigator.language || "en", { month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(`${month}-01T12:00:00Z`));
}

/* ---- API helpers -------------------------------------------------------------------------- */

/** Multipart upload, which the typed JSON client doesn't do. */
async function uploadReceipt(id: string, file: File): Promise<Expense> {
  const body = new FormData();
  body.append("file", file);
  const res = await fetch(`/api/v1/expenses/${id}/receipt`, { method: "POST", credentials: "include", headers: authHeaders(), body });
  const json = await res.json().catch(() => null);
  if (!res.ok) {
    throw new ApiError(res.status, json && typeof json === "object" && "message" in json ? json : { code: `http_${res.status}`, message: res.statusText });
  }
  return json as Expense;
}

const receiptUrl = (id: string) => `/api/v1/expenses/${id}/receipt`;

/**
 * Receipts open as a plain link. The server picks the account from the session when the user has
 * only one; with several, the account header is required, so fetch the file and open it as a blob.
 */
function openReceipt(e: MouseEvent<HTMLAnchorElement>, id: string, multiAccount: boolean) {
  if (!multiAccount) return;
  e.preventDefault();
  const tab = window.open("", "_blank");
  void fetch(receiptUrl(id), { credentials: "include", headers: authHeaders() })
    .then((r) => (r.ok ? r.blob() : Promise.reject(new Error(r.statusText))))
    .then((blob) => {
      const url = URL.createObjectURL(blob);
      if (tab) tab.location.href = url;
      else window.location.assign(url);
    })
    .catch(() => tab?.close());
}

class ReceiptFailed extends Error {
  constructor(
    readonly expense: Expense,
    reason: unknown,
  ) {
    super(errorInfo(reason).message);
  }
}

function isEditable(x: Expense, isAdmin: boolean) {
  if (x.is_locked || x.invoice_id || x.approval_state === "approved") return false;
  return !(x.approval_state === "submitted" && !isAdmin);
}

const categoriesQuery = { queryKey: ["expense_categories"], queryFn: () => unwrap(api.GET("/api/v1/expense_categories")) };

/* ---- Page --------------------------------------------------------------------------------- */

export function ExpensesPage() {
  const { t } = useTranslation();
  const me = useMe();
  const perms = usePermissions();
  const account = useAccountSettings();
  const thisMonth = account.today.slice(0, 7);
  const [month, setMonth] = useState<string | null>(null);
  const current = month ?? thisMonth;
  const { from, to } = monthRange(current);
  const [dialog, setDialog] = useState<{ open: boolean; expense: Expense | null }>({ open: false, expense: null });

  const q = useQuery({
    queryKey: ["expenses", "mine", me.current_membership_id, from, to],
    queryFn: () =>
      unwrap(
        api.GET("/api/v1/expenses", {
          params: { query: { from, to, membership_id: me.current_membership_id ?? undefined, limit: 500 } },
        }),
      ),
    enabled: account.loaded,
  });
  const categories = useQuery(categoriesQuery);
  const units = new Map((categories.data ?? []).map((c) => [c.id, c.unit_name]));
  const expenses = q.data?.data ?? [];
  const multiAccount = me.accounts.length > 1;

  const prev = () => setMonth(shiftMonth(current, -1));
  const next = () => setMonth(shiftMonth(current, 1));
  const add = () => setDialog({ open: true, expense: null });
  useHotkeys({ ArrowLeft: prev, ArrowRight: next, n: add });

  // Totals per currency: projects bill in their client's currency.
  const totals = new Map<string, number>();
  expenses.forEach((x) => totals.set(x.currency, (totals.get(x.currency) ?? 0) + x.amount));
  const currencies = [...totals.keys()].sort();

  return (
    <div className="page">
      <PageHeader
        title={t("expenses.title")}
        actions={
          <>
            <div className="month-nav" role="group" aria-label={t("expenses.month")}>
              <Button size="sm" variant="ghost" onClick={prev} aria-label={t("expenses.previousMonth")}>
                ‹
              </Button>
              <span className="month-label" aria-live="polite">
                {formatMonth(current)}
              </span>
              <Button size="sm" variant="ghost" onClick={next} aria-label={t("expenses.nextMonth")}>
                ›
              </Button>
              <Button size="sm" onClick={() => setMonth(null)} disabled={current === thisMonth}>
                {t("expenses.thisMonth")}
              </Button>
            </div>
            <Button variant="primary" onClick={add}>
              {t("expenses.add")} <Kbd>N</Kbd>
            </Button>
          </>
        }
      />

      {q.isError ? (
        <p className="notice notice-error" role="alert">
          {errorInfo(q.error).message}
        </p>
      ) : !q.isLoading && account.loaded && expenses.length === 0 ? (
        <EmptyState
          title={t("expenses.emptyTitle", { month: formatMonth(current) })}
          body={t("expenses.emptyBody")}
          action={
            <Button variant="primary" onClick={add}>
              {t("expenses.add")}
            </Button>
          }
        />
      ) : (
        <table className="ledger">
          <thead>
            <tr>
              <th>{t("expenses.date")}</th>
              <th>{t("expenses.project")}</th>
              <th>{t("expenses.category")}</th>
              <th>{t("expenses.notes")}</th>
              <th>{t("expenses.billable")}</th>
              <th>{t("expenses.receipt")}</th>
              <th className="num">{t("expenses.amount")}</th>
              <th>
                <span className="sr-only">{t("expenses.actions")}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {(q.isLoading || !account.loaded) && <LoadingRow colSpan={8} />}
            {expenses.map((x) => {
              const editable = isEditable(x, perms.isAdmin);
              return (
                <tr key={x.id}>
                  <td style={{ whiteSpace: "nowrap" }}>{formatDate(x.spent_date)}</td>
                  <td>
                    {x.project.code ? `[${x.project.code}] ` : ""}
                    {x.project.name} <span className="muted">({x.client.name})</span>
                  </td>
                  <td>
                    {x.category.name}
                    {x.units !== undefined && x.units !== null && (
                      <span className="expense-sub">{t("expenses.unitsOf", { units: x.units, unit: units.get(x.category.id) ?? "" })}</span>
                    )}
                  </td>
                  <td className="expense-notes">{x.notes}</td>
                  <td>{x.billable ? t("app.yes") : <span className="muted">{t("app.no")}</span>}</td>
                  <td>
                    {x.has_receipt && (
                      <a
                        href={receiptUrl(x.id)}
                        target="_blank"
                        rel="noopener"
                        title={x.receipt_filename}
                        onClick={(e) => openReceipt(e, x.id, multiAccount)}
                      >
                        {t("expenses.viewReceipt")}
                      </a>
                    )}
                  </td>
                  <td className="num">{formatMoney(x.amount, x.currency)}</td>
                  <td className="num" style={{ width: 1, whiteSpace: "nowrap" }}>
                    {editable ? (
                      <div className="row" style={{ justifyContent: "flex-end" }}>
                        {x.approval_state === "rejected" && <StateBadge entry={x} />}
                        <Button size="sm" variant="ghost" onClick={() => setDialog({ open: true, expense: x })} aria-label={t("expenses.editNamed", { name: x.category.name, date: formatDate(x.spent_date) })}>
                          {t("app.edit")}
                        </Button>
                      </div>
                    ) : (
                      <StateBadge entry={x} />
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
          {expenses.length > 0 && (
            <tfoot>
              {currencies.map((c) => (
                <tr key={c}>
                  <td colSpan={6} className="num muted">
                    {currencies.length > 1 ? t("expenses.totalIn", { currency: c }) : t("expenses.total")}
                  </td>
                  <td className="num total">{formatMoney(totals.get(c) ?? 0, c)}</td>
                  <td />
                </tr>
              ))}
            </tfoot>
          )}
        </table>
      )}

      <ExpenseDialog
        open={dialog.open}
        expense={dialog.expense}
        defaultDate={current === thisMonth ? account.today : from}
        lastProjectId={expenses[0]?.project.id}
        onOpenChange={(open) => setDialog((d) => ({ ...d, open }))}
      />
    </div>
  );
}

/* ---- Add / edit dialog -------------------------------------------------------------------- */

interface Form {
  date: string;
  projectId: string | undefined;
  categoryId: string | undefined;
  amount: number | null;
  units: string;
  notes: string;
  billable: boolean;
}

function parseUnits(s: string): number | null {
  const v = s.trim().replace(",", ".");
  if (!v) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function ExpenseDialog({
  open,
  expense,
  defaultDate,
  lastProjectId,
  onOpenChange,
}: {
  open: boolean;
  expense: Expense | null;
  defaultDate: string;
  lastProjectId?: string;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const perms = usePermissions();
  const me = useMe();
  const account = useAccountSettings();
  const assignments = useAssignments();
  const categories = useQuery(categoriesQuery);
  // After a failed receipt upload the expense exists: keep editing it instead of creating another.
  const [current, setCurrent] = useState<Expense | null>(expense);
  const [form, setForm] = useState<Form>({ date: defaultDate, projectId: undefined, categoryId: undefined, amount: null, units: "", notes: "", billable: true });
  const [file, setFile] = useState<File | null>(null);
  const [removeReceipt, setRemoveReceipt] = useState(false);
  const [fileKey, setFileKey] = useState(0);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const projects = assignments.data ?? [];
  const project = projects.find((a) => a.project_id === form.projectId);
  const allCategories = categories.data ?? [];
  const category = allCategories.find((c) => c.id === form.categoryId);
  const choices = allCategories.filter((c) => c.is_active || c.id === current?.category.id);

  useEffect(() => {
    if (!open) return;
    setCurrent(expense);
    const initialProject = expense?.project.id ?? (projects.some((a) => a.project_id === lastProjectId) ? lastProjectId : projects.length === 1 ? projects[0].project_id : undefined);
    setForm({
      date: expense?.spent_date ?? defaultDate,
      projectId: initialProject,
      categoryId: expense?.category.id,
      amount: expense ? expense.amount : null,
      units: expense?.units !== undefined && expense?.units !== null ? String(expense.units) : "",
      notes: expense?.notes ?? "",
      billable: expense?.billable ?? projects.find((a) => a.project_id === initialProject)?.is_billable ?? true,
    });
    setFile(null);
    setRemoveReceipt(false);
    setFileKey((k) => k + 1);
    setConfirmDelete(false);
    save.reset();
    remove.reset();
  }, [open, expense]);

  const set = <K extends keyof Form>(k: K) => (v: Form[K]) => setForm((f) => ({ ...f, [k]: v }));
  const invalidate = () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: ["expenses"] }),
      qc.invalidateQueries({ queryKey: ["approvals"] }),
      qc.invalidateQueries({ queryKey: ["projects"] }),
    ]);

  const save = useMutation({
    mutationFn: async (): Promise<Expense> => {
      const fields: Record<string, unknown> = {
        spent_date: form.date,
        project_id: form.projectId,
        category_id: form.categoryId,
        notes: form.notes,
      };
      if (category?.is_unit_priced) fields.units = parseUnits(form.units) ?? undefined;
      else fields.amount = form.amount ?? undefined;
      if (project?.is_billable) fields.billable = form.billable;

      let saved: Expense;
      if (current) {
        // Send only what changed: moving the date re-checks that the week is open.
        const was: Record<string, unknown> = {
          spent_date: current.spent_date,
          project_id: current.project.id,
          category_id: current.category.id,
          notes: current.notes ?? "",
          units: current.units ?? undefined,
          amount: current.amount,
          billable: current.billable,
        };
        const body = Object.fromEntries(Object.entries(fields).filter(([k, v]) => v !== undefined && v !== was[k]));
        saved = Object.keys(body).length
          ? await unwrap(api.PATCH("/api/v1/expenses/{id}", { params: { path: { id: current.id } }, body: body as never }))
          : current;
      } else {
        saved = await unwrap(api.POST("/api/v1/expenses", { body: fields as never }));
      }
      try {
        if (file) saved = await uploadReceipt(saved.id, file);
        else if (removeReceipt && saved.has_receipt) {
          saved = await unwrap(api.DELETE("/api/v1/expenses/{id}/receipt", { params: { path: { id: saved.id } } }));
        }
      } catch (e) {
        throw new ReceiptFailed(saved, e);
      }
      return saved;
    },
    onSuccess: async () => {
      await invalidate();
      toast(current ? t("expenses.saved") : t("expenses.added"));
      onOpenChange(false);
    },
    onError: (e) => {
      if (e instanceof ReceiptFailed) {
        setCurrent(e.expense);
        void invalidate();
      }
    },
  });
  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/expenses/{id}", { params: { path: { id: current!.id } } })),
    onSuccess: async () => {
      await invalidate();
      toast(t("expenses.deleted"));
      setConfirmDelete(false);
      onOpenChange(false);
    },
    onError: () => setConfirmDelete(false),
  });

  const receiptError = save.error instanceof ReceiptFailed ? save.error : null;
  const err = save.error && !receiptError ? errorInfo(save.error) : null;
  const removeErr = remove.error ? errorInfo(remove.error) : null;
  const currency = project?.client.currency ?? current?.currency ?? account.currency;
  const unitCount = parseUnits(form.units);
  const unitName = category?.unit_name ?? "";
  const computed = category?.is_unit_priced && category.unit_price !== undefined && unitCount !== null ? Math.round(unitCount * category.unit_price) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate();
  };

  const projectOptions = projects.map((a) => ({
    value: a.project_id,
    label: `${a.project_code ? `[${a.project_code}] ` : ""}${a.project_name} (${a.client.name})`,
  }));
  if (current && !projects.some((a) => a.project_id === current.project.id)) {
    projectOptions.unshift({ value: current.project.id, label: `${current.project.name} (${current.client.name})` });
  }

  return (
    <>
      <Dialog open={open && !confirmDelete} onOpenChange={onOpenChange} title={current ? t("expenses.editTitle") : t("expenses.addTitle")}>
        {!assignments.isLoading && projects.length === 0 && !current ? (
          <>
            <p className="notice">{t("expenses.noAssignments")}</p>
            <DialogActions>
              <Button onClick={() => onOpenChange(false)}>{t("app.close")}</Button>
            </DialogActions>
          </>
        ) : !categories.isLoading && choices.length === 0 ? (
          <>
            <p className="notice">{perms.isAdmin ? t("expenses.noCategoriesAdmin") : t("expenses.noCategories")}</p>
            <DialogActions>
              <Button onClick={() => onOpenChange(false)}>{t("app.close")}</Button>
            </DialogActions>
          </>
        ) : (
          <form className="stack" onSubmit={submit}>
            {receiptError && (
              <p className="notice notice-warn" role="alert">
                {t("expenses.receiptFailed", { message: receiptError.message })}
              </p>
            )}
            {(err && !Object.keys(err.fields).length) || removeErr ? (
              <p className="notice notice-error" role="alert">
                {(removeErr ?? err)!.message}
              </p>
            ) : null}
            <div className="form-grid">
              <TextField label={t("expenses.date")} type="date" value={form.date} onChange={set("date")} error={err?.fields.spent_date} required />
              <SelectField
                label={t("expenses.category")}
                value={form.categoryId}
                onChange={set("categoryId")}
                placeholder={t("expenses.chooseCategory")}
                options={choices.map((c) => ({ value: c.id, label: c.name }))}
                error={err?.fields.category_id}
              />
              <SelectField
                className="span-2"
                label={t("expenses.project")}
                value={form.projectId}
                onChange={(v) => {
                  const p = projects.find((a) => a.project_id === v);
                  setForm((f) => ({ ...f, projectId: v, billable: p?.is_billable ?? f.billable }));
                }}
                placeholder={t("expenses.chooseProject")}
                options={projectOptions}
                error={err?.fields.project_id}
              />
              {category?.is_unit_priced ? (
                <Field
                  label={t("expenses.unitsLabel", { unit: unitName })}
                  hint={
                    computed !== null
                      ? t("expenses.computed", { amount: formatMoney(computed, currency) })
                      : category.unit_price !== undefined
                        ? t("expenses.unitPrice", { price: formatMoney(category.unit_price, currency), unit: unitName })
                        : t("expenses.unitsHint")
                  }
                  error={err?.fields.units}
                >
                  {(a11y) => (
                    <div className="input-affix">
                      <input {...a11y} className="input input-num" inputMode="decimal" value={form.units} onChange={(e) => set("units")(e.target.value)} />
                      <span className="input-affix-label">{unitName}</span>
                    </div>
                  )}
                </Field>
              ) : (
                <MoneyField
                  label={t("expenses.amount")}
                  hint={project ? t("expenses.amountHint", { currency }) : undefined}
                  value={form.amount}
                  onChange={set("amount")}
                  currency={currency}
                  error={err?.fields.amount}
                />
              )}
              <div style={{ alignSelf: "center" }}>
                {project?.is_billable ? (
                  <Checkbox checked={form.billable} onChange={set("billable")} label={t("expenses.billableLabel")} hint={t("expenses.billableHint")} />
                ) : project ? (
                  <span className="field-hint">{t("expenses.projectNotBillable")}</span>
                ) : null}
              </div>
            </div>
            <TextAreaField label={t("expenses.notes")} value={form.notes} onChange={set("notes")} rows={2} error={err?.fields.notes} />
            <Field label={t("expenses.receipt")} hint={t("expenses.receiptHint")} error={receiptError ? undefined : err?.fields.file}>
              {(a11y) => (
                <div className="stack" style={{ gap: 8 }}>
                  {current?.has_receipt && !file && (
                    <div className="row">
                      {removeReceipt ? (
                        <span className="muted spacer">{t("expenses.receiptWillBeRemoved")}</span>
                      ) : (
                        <a
                          className="spacer"
                          href={receiptUrl(current.id)}
                          target="_blank"
                          rel="noopener"
                          onClick={(e) => openReceipt(e, current.id, me.accounts.length > 1)}
                        >
                          {current.receipt_filename ?? t("expenses.viewReceipt")}
                        </a>
                      )}
                      <Button size="sm" variant="ghost" onClick={() => setRemoveReceipt((r) => !r)}>
                        {removeReceipt ? t("expenses.keepReceipt") : t("expenses.removeReceipt")}
                      </Button>
                    </div>
                  )}
                  <input
                    key={fileKey}
                    {...a11y}
                    type="file"
                    className="file-input"
                    accept="image/jpeg,image/png,image/webp,image/heic,image/gif,application/pdf"
                    onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                  />
                </div>
              )}
            </Field>
            <DialogActions>
              {current && (
                <Button variant="danger" onClick={() => setConfirmDelete(true)} style={{ marginRight: "auto" }}>
                  {t("app.delete")}
                </Button>
              )}
              <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
              <Button type="submit" variant="primary" busy={save.isPending} disabled={!form.projectId || !form.categoryId}>
                {current ? t("app.save") : t("expenses.add")}
              </Button>
            </DialogActions>
          </form>
        )}
      </Dialog>
      <ConfirmDialog
        open={open && confirmDelete}
        onOpenChange={(o) => !o && setConfirmDelete(false)}
        title={t("expenses.deleteTitle")}
        body={t("expenses.deleteBody")}
        confirmLabel={t("expenses.delete")}
        busy={remove.isPending}
        onConfirm={() => remove.mutate()}
      />
    </>
  );
}
