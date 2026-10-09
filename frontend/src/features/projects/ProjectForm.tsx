// SPDX-License-Identifier: AGPL-3.0-only
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, DurationField, Field, MoneyField, SelectField, TextAreaField, TextField, type Option } from "../../design";
import type { Schemas } from "../../lib/api";
import { useFeature } from "../../lib/features";
import type { DurationStyle } from "../../lib/format";
import { usePermissions } from "../../lib/session";
import { BILL_BY, BUDGET_BY, isLineBudget, isMoneyBudget } from "./labels";
import type { Client, Nullable, Project } from "./queries";

type ProjectInput = Schemas["ProjectInput"];

/** Everything the create form and the Settings tab edit. Text inputs hold strings; money and durations hold numbers. */
export interface ProjectFormState {
  client_id: string;
  name: string;
  code: string;
  is_billable: boolean;
  bill_by: string;
  hourly_rate: number | null;
  is_fixed_fee: boolean;
  fee_amount: number | null;
  budget_by: string;
  budget_seconds: number | null;
  budget_amount: number | null;
  budget_is_monthly: boolean;
  budget_include_expenses: boolean;
  budget_alert_percent: string;
  notify_when_over_budget: boolean;
  show_budget_to_all: boolean;
  starts_on: string;
  ends_on: string;
  notes: string;
}

export function emptyProjectForm(clientId = ""): ProjectFormState {
  return {
    client_id: clientId,
    name: "",
    code: "",
    is_billable: true,
    bill_by: "project",
    hourly_rate: null,
    is_fixed_fee: false,
    fee_amount: null,
    budget_by: "none",
    budget_seconds: null,
    budget_amount: null,
    budget_is_monthly: false,
    budget_include_expenses: false,
    budget_alert_percent: "80",
    notify_when_over_budget: false,
    show_budget_to_all: false,
    starts_on: "",
    ends_on: "",
    notes: "",
  };
}

export function projectToForm(p: Project): ProjectFormState {
  return {
    client_id: p.client.id,
    name: p.name,
    code: p.code ?? "",
    is_billable: p.is_billable,
    bill_by: p.bill_by,
    hourly_rate: p.hourly_rate ?? null,
    is_fixed_fee: p.is_fixed_fee,
    fee_amount: p.fee_amount ?? null,
    budget_by: p.budget_by,
    budget_seconds: p.budget_seconds ?? null,
    budget_amount: p.budget_amount ?? null,
    budget_is_monthly: p.budget_is_monthly,
    budget_include_expenses: p.budget_include_expenses,
    budget_alert_percent: p.budget_alert_percent === undefined || p.budget_alert_percent === null ? "" : String(Number(p.budget_alert_percent)),
    notify_when_over_budget: p.notify_when_over_budget,
    show_budget_to_all: p.show_budget_to_all,
    starts_on: p.starts_on ?? "",
    ends_on: p.ends_on ?? "",
    notes: p.notes ?? "",
  };
}

const percentText = (s: string) => s.trim().replace(",", ".");

/** Checks what the server can't: a percentage it could not even parse. */
export function validateProjectForm(f: ProjectFormState, t: TFunction): Record<string, string> {
  const errors: Record<string, string> = {};
  const p = percentText(f.budget_alert_percent);
  if (p !== "" && (!/^\d+(\.\d+)?$/.test(p) || Number(p) <= 0 || Number(p) > 100)) {
    errors.budget_alert_percent = t("projects.errors.alertPercent");
  }
  return errors;
}

/** The API value for each form field: blank text becomes null. */
function toApi(f: ProjectFormState): Nullable<ProjectInput> {
  const text = (s: string) => (s.trim() === "" ? null : s.trim());
  const percent = percentText(f.budget_alert_percent);
  return {
    client_id: f.client_id || null,
    name: f.name.trim(),
    code: text(f.code),
    is_billable: f.is_billable,
    bill_by: f.bill_by,
    hourly_rate: f.hourly_rate,
    is_fixed_fee: f.is_fixed_fee,
    fee_amount: f.fee_amount,
    budget_by: f.budget_by,
    budget_seconds: f.budget_seconds,
    budget_amount: f.budget_amount,
    budget_is_monthly: f.budget_is_monthly,
    budget_include_expenses: f.budget_include_expenses,
    budget_alert_percent: percent === "" ? null : Number(percent),
    notify_when_over_budget: f.notify_when_over_budget,
    show_budget_to_all: f.show_budget_to_all,
    starts_on: text(f.starts_on),
    ends_on: text(f.ends_on),
    notes: text(f.notes),
  };
}

const MONEY_FIELDS: readonly string[] = ["hourly_rate", "fee_amount", "budget_amount"];

/** Body for POST /projects: only the values that apply to the chosen billing and budget. */
export function formToCreateBody(f: ProjectFormState, canSeeRates: boolean): ProjectInput {
  const body = toApi(f);
  if (!(f.is_billable && f.bill_by === "project")) body.hourly_rate = null;
  if (!f.is_fixed_fee) body.fee_amount = null;
  if (f.budget_by !== "project") body.budget_seconds = null;
  if (f.budget_by !== "project_cost") {
    body.budget_amount = null;
    body.budget_include_expenses = null;
  }
  if (f.budget_by === "none") {
    body.budget_alert_percent = null;
    body.budget_is_monthly = null;
    body.notify_when_over_budget = null;
  }
  if (!canSeeRates) for (const k of MONEY_FIELDS) delete body[k as keyof ProjectInput];
  // Absent and null mean the same on create; drop nulls to keep the typed body honest.
  return Object.fromEntries(Object.entries(body).filter(([, v]) => v !== null)) as ProjectInput;
}

/** Body for PATCH /projects/{id}: only the fields that changed, with null to clear a value. */
export function formToPatch(before: ProjectFormState, after: ProjectFormState, canSeeRates: boolean): Nullable<ProjectInput> {
  const a = toApi(before);
  const b = toApi(after);
  const patch: Record<string, unknown> = {};
  for (const key of Object.keys(b) as (keyof ProjectInput)[]) {
    if (!canSeeRates && MONEY_FIELDS.includes(key)) continue;
    if (a[key] !== b[key]) patch[key] = b[key];
  }
  return patch as Nullable<ProjectInput>;
}

/** Changing any of these changes the billable rate of entries already on the project. */
export const RATE_FIELDS: (keyof ProjectInput)[] = ["is_billable", "bill_by", "hourly_rate"];

interface ProjectFieldsProps {
  form: ProjectFormState;
  onChange: (patch: Partial<ProjectFormState>) => void;
  errors: Record<string, string | undefined>;
  clients: Client[];
  currency: string;
  durationStyle: DurationStyle;
  /** "create" explains that per-task and per-person settings come after the project exists. */
  mode: "create" | "edit";
  onNewClient?: () => void;
}

export function ProjectFields({ form, onChange, errors, clients, currency, durationStyle, mode, onNewClient }: ProjectFieldsProps) {
  const { t } = useTranslation();
  const perms = usePermissions();
  const set =
    <K extends keyof ProjectFormState>(key: K) =>
    (value: ProjectFormState[K]) =>
      onChange({ [key]: value } as Partial<ProjectFormState>);

  const clientOptions: Option[] = clients.map((c) => ({ value: c.id, label: c.name }));
  const billByOptions: Option[] = BILL_BY.map((b) => ({ value: b, label: t(`projects.billBy.${b}`) }));
  // Money budgets need rate visibility; keep the current value selectable so it isn't lost.
  const budgetByOptions: Option[] = BUDGET_BY.filter((b) => perms.canSeeRates || !isMoneyBudget(b) || b === form.budget_by).map((b) => ({
    value: b,
    label: t(`projects.budgetBy.${b}`),
  }));
  const hasBudget = form.budget_by !== "none";
  // Budgets are a switched-off feature unless the server says otherwise (lib/features); a budget
  // set earlier is kept as it is.
  const budgets = useFeature("budgets");
  const perLine = mode === "create" && (form.bill_by === "tasks" || form.bill_by === "people");

  return (
    <>
      <section className="form-section">
        <h2>{t("projects.form.basics")}</h2>
        <div className="form-grid">
          <div className="span-2 field-with-action">
            <SelectField
              label={t("projects.fields.client")}
              value={form.client_id || undefined}
              onChange={set("client_id")}
              options={clientOptions}
              placeholder={clients.length ? t("projects.form.chooseClient") : t("projects.form.noClients")}
              error={errors.client_id}
            />
            {onNewClient && <Button onClick={onNewClient}>{t("clients.newClient")}</Button>}
          </div>
          <TextField
            label={t("projects.fields.name")}
            value={form.name}
            onChange={set("name")}
            error={errors.name}
            required
            autoFocus={mode === "create"}
            autoComplete="off"
          />
          <TextField
            label={t("projects.fields.code")}
            hint={t("projects.fields.codeHint")}
            value={form.code}
            onChange={set("code")}
            error={errors.code}
            autoComplete="off"
          />
        </div>
      </section>

      <section className="form-section">
        <h2>{t("projects.form.billing")}</h2>
        <div className="stack">
          <Checkbox checked={form.is_billable} onChange={set("is_billable")} label={t("projects.fields.billable")} hint={t("projects.fields.billableHint")} />
          {form.is_billable && (
            <div className="form-grid">
              <SelectField
                label={t("projects.fields.billBy")}
                hint={t(`projects.billByHint.${form.bill_by}${perLine ? "Create" : ""}`)}
                value={form.bill_by}
                onChange={set("bill_by")}
                options={billByOptions}
                error={errors.bill_by}
              />
              {form.bill_by === "project" && perms.canSeeRates ? (
                <MoneyField
                  label={t("projects.fields.hourlyRate")}
                  hint={t("projects.fields.hourlyRateHint")}
                  value={form.hourly_rate}
                  onChange={set("hourly_rate")}
                  currency={currency}
                  error={errors.hourly_rate}
                />
              ) : (
                <div />
              )}
            </div>
          )}
          <Checkbox checked={form.is_fixed_fee} onChange={set("is_fixed_fee")} label={t("projects.fields.fixedFee")} hint={t("projects.fields.fixedFeeHint")} />
          {form.is_fixed_fee && perms.canSeeRates && (
            <div className="form-grid">
              <MoneyField label={t("projects.fields.feeAmount")} value={form.fee_amount} onChange={set("fee_amount")} currency={currency} error={errors.fee_amount} />
            </div>
          )}
        </div>
      </section>

      {budgets && (
        <section className="form-section">
          <h2>{t("projects.form.budget")}</h2>
          <div className="stack">
            <div className="form-grid">
              <SelectField label={t("projects.fields.budgetBy")} value={form.budget_by} onChange={set("budget_by")} options={budgetByOptions} error={errors.budget_by} />
              {form.budget_by === "project" ? (
                <DurationField
                  label={t("projects.fields.budgetHours")}
                  hint={t("projects.fields.budgetHoursHint")}
                  value={form.budget_seconds}
                  onChange={set("budget_seconds")}
                  style={durationStyle}
                  error={errors.budget_seconds}
                />
              ) : form.budget_by === "project_cost" && perms.canSeeRates ? (
                <MoneyField
                  label={t("projects.fields.budgetAmount")}
                  value={form.budget_amount}
                  onChange={set("budget_amount")}
                  currency={currency}
                  error={errors.budget_amount}
                />
              ) : (
                <div />
              )}
            </div>
            {isLineBudget(form.budget_by) && <p className="muted inline-note">{t(`projects.form.lineBudget.${form.budget_by}${mode === "create" ? "Create" : ""}`)}</p>}
            {hasBudget && (
              <>
                {form.budget_by === "project_cost" && (
                  <Checkbox checked={form.budget_include_expenses} onChange={set("budget_include_expenses")} label={t("projects.fields.includeExpenses")} />
                )}
                <Checkbox checked={form.budget_is_monthly} onChange={set("budget_is_monthly")} label={t("projects.fields.monthly")} hint={t("projects.fields.monthlyHint")} />
                <div className="form-grid">
                  <PercentField
                    label={t("projects.fields.alertPercent")}
                    hint={t("projects.fields.alertPercentHint")}
                    value={form.budget_alert_percent}
                    onChange={set("budget_alert_percent")}
                    error={errors.budget_alert_percent}
                  />
                </div>
                <Checkbox
                  checked={form.notify_when_over_budget}
                  onChange={set("notify_when_over_budget")}
                  label={t("projects.fields.notify")}
                  hint={t("projects.fields.notifyHint")}
                />
                <Checkbox checked={form.show_budget_to_all} onChange={set("show_budget_to_all")} label={t("projects.fields.showBudget")} hint={t("projects.fields.showBudgetHint")} />
              </>
            )}
          </div>
        </section>
      )}

      <section className="form-section">
        <h2>{t("projects.form.datesNotes")}</h2>
        <div className="form-grid">
          <TextField type="date" label={t("projects.fields.startsOn")} value={form.starts_on} onChange={set("starts_on")} error={errors.starts_on} />
          <TextField type="date" label={t("projects.fields.endsOn")} value={form.ends_on} onChange={set("ends_on")} error={errors.ends_on} />
          <TextAreaField
            label={t("projects.fields.notes")}
            hint={t("projects.fields.notesHint")}
            value={form.notes}
            onChange={set("notes")}
            error={errors.notes}
            rows={3}
            fieldClassName="span-2"
          />
        </div>
      </section>
    </>
  );
}

function PercentField({ label, hint, value, onChange, error }: { label: string; hint?: string; value: string; onChange: (v: string) => void; error?: string }) {
  return (
    <Field label={label} hint={hint} error={error}>
      {(a11y) => (
        <div className="input-affix">
          <input {...a11y} className="input input-num" inputMode="decimal" value={value} onChange={(e) => onChange(e.target.value)} />
          <span className="input-affix-label">%</span>
        </div>
      )}
    </Field>
  );
}
