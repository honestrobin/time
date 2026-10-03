// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, SelectField, TextField, useToast } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { CURRENCIES, TIME_ZONES } from "../../lib/reference";
import { usePermissions } from "../../lib/session";
import { AccountDataSection } from "./AccountData";

type Account = Schemas["AccountView"];
type BoolKeys = "approvals_enabled" | "timesheet_reminders_enabled" | "require_two_factor";
type Form = Record<Exclude<keyof Schemas["AccountUpdate"], BoolKeys>, string> & Record<BoolKeys, boolean>;

export const accountQuery = { queryKey: ["account"], queryFn: () => unwrap(api.GET("/api/v1/account")) };

function toForm(a: Account): Form {
  return {
    name: a.name,
    timezone: a.timezone,
    week_start: String(a.week_start),
    default_currency: a.default_currency,
    time_rounding_minutes: String(a.time_rounding_minutes),
    time_rounding_mode: a.time_rounding_mode,
    locale: a.locale,
    legal_name: a.legal_name ?? "",
    address_line1: a.address_line1 ?? "",
    address_line2: a.address_line2 ?? "",
    postal_code: a.postal_code ?? "",
    city: a.city ?? "",
    region: a.region ?? "",
    country_code: a.country_code ?? "",
    vat_id: a.vat_id ?? "",
    company_reg_no: a.company_reg_no ?? "",
    peppol_scheme: a.peppol_scheme ?? "",
    peppol_id: a.peppol_id ?? "",
    iban: a.iban ?? "",
    bic: a.bic ?? "",
    approvals_enabled: a.approvals_enabled,
    timesheet_reminders_enabled: a.timesheet_reminders_enabled,
    require_two_factor: a.require_two_factor,
    duration_format: a.duration_format,
  };
}

export function AccountSettingsPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const perms = usePermissions();
  const { data } = useQuery(accountQuery);
  const [form, setForm] = useState<Form | null>(null);
  useEffect(() => {
    if (data && !form) setForm(toForm(data));
  }, [data, form]);

  const save = useMutation({
    mutationFn: (f: Form) =>
      unwrap(
        api.PATCH("/api/v1/account", {
          body: { ...f, week_start: Number(f.week_start), time_rounding_minutes: Number(f.time_rounding_minutes) },
        }),
      ),
    onSuccess: (a) => {
      qc.setQueryData(accountQuery.queryKey, a);
      setForm(toForm(a));
      void qc.invalidateQueries({ queryKey: ["me"] });
      toast(t("app.saved"));
    },
  });

  if (!form) return <div className="page">{t("app.loading")}</div>;
  const err = save.error ? errorInfo(save.error) : null;
  const set = (k: keyof Form) => (v: string | boolean) => setForm((f) => (f ? { ...f, [k]: v } : f));
  const text = (k: Exclude<keyof Form, BoolKeys>, label: string, extra: Partial<Parameters<typeof TextField>[0]> = {}) => (
    <TextField label={t(label)} value={form[k]} onChange={set(k)} error={err?.fields[k]} disabled={!perms.isAdmin} {...extra} />
  );
  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate(form);
  };

  return (
    <div className="page page-narrow">
      <header className="page-header">
        <h1>{t("settings.accountTitle")}</h1>
      </header>
      {!perms.isAdmin && <p className="notice">{t("settings.adminOnly")}</p>}
      <form onSubmit={submit} className="stack">
        <section>
          <h2 style={{ marginBottom: 12 }}>{t("settings.general")}</h2>
          <div className="form-grid">
            {text("name", "settings.accountName", { fieldClassName: "span-2" })}
            <SelectField
              label={t("settings.timezone")}
              value={form.timezone}
              onChange={set("timezone")}
              options={(TIME_ZONES.includes(form.timezone) ? TIME_ZONES : [form.timezone, ...TIME_ZONES]).map((z) => ({ value: z, label: z }))}
              disabled={!perms.isAdmin}
            />
            <SelectField
              label={t("settings.weekStart")}
              value={form.week_start}
              onChange={set("week_start")}
              options={["1", "6", "7"].map((d) => ({ value: d, label: t(`weekday.${d}`) }))}
              disabled={!perms.isAdmin}
            />
            <SelectField
              label={t("settings.defaultCurrency")}
              value={form.default_currency}
              onChange={set("default_currency")}
              options={CURRENCIES.map((c) => ({ value: c, label: c }))}
              disabled={!perms.isAdmin}
            />
            <SelectField
              label={t("settings.rounding")}
              hint={t("settings.roundingHint")}
              value={form.time_rounding_minutes}
              onChange={set("time_rounding_minutes")}
              options={["0", "6", "15", "30"].map((m) => ({
                value: m,
                label: m === "0" ? t("settings.roundingNone") : t("settings.roundingMinutes", { count: Number(m) }),
              }))}
              disabled={!perms.isAdmin}
            />
            {form.time_rounding_minutes !== "0" && (
              <SelectField
                label={t("settings.roundingMode")}
                hint={t(form.time_rounding_mode === "nearest" ? "settings.roundingNearestHint" : "settings.roundingUpHint")}
                value={form.time_rounding_mode}
                onChange={set("time_rounding_mode")}
                options={[
                  { value: "up", label: t("settings.roundingUp") },
                  { value: "nearest", label: t("settings.roundingNearest") },
                ]}
                disabled={!perms.isAdmin}
              />
            )}
          </div>
        </section>
        <section className="section">
          <h2 style={{ marginBottom: 12 }}>{t("settings.timesheets")}</h2>
          <div className="stack">
            <SelectField
              label={t("settings.durationFormat")}
              value={form.duration_format}
              onChange={set("duration_format")}
              options={[
                { value: "hm", label: t("settings.durationHm") },
                { value: "decimal", label: t("settings.durationDecimal") },
              ]}
              disabled={!perms.isAdmin}
            />
            <Checkbox
              checked={form.approvals_enabled}
              onChange={set("approvals_enabled")}
              label={t("settings.approvals")}
              hint={t("settings.approvalsHint")}
              disabled={!perms.isAdmin}
            />
            <Checkbox
              checked={form.timesheet_reminders_enabled}
              onChange={set("timesheet_reminders_enabled")}
              label={t("settings.reminders")}
              hint={t("settings.remindersHint")}
              disabled={!perms.isAdmin}
            />
          </div>
        </section>
        <section className="section">
          <h2 style={{ marginBottom: 12 }}>{t("settings.security")}</h2>
          <Checkbox
            checked={form.require_two_factor}
            onChange={set("require_two_factor")}
            label={t("settings.requireTwoFactor")}
            hint={t("settings.requireTwoFactorHint")}
            disabled={!perms.isAdmin}
          />
        </section>
        <section className="section">
          <h2>{t("settings.fiscal")}</h2>
          <p className="muted" style={{ marginBottom: 12 }}>
            {t("settings.fiscalLead")}
          </p>
          <div className="form-grid">
            {text("legal_name", "settings.legalName", { fieldClassName: "span-2" })}
            {text("address_line1", "settings.addressLine1", { fieldClassName: "span-2" })}
            {text("address_line2", "settings.addressLine2", { fieldClassName: "span-2" })}
            {text("postal_code", "settings.postalCode")}
            {text("city", "settings.city")}
            {text("region", "settings.region")}
            {text("country_code", "settings.country", { maxLength: 2 })}
            {text("vat_id", "settings.vatId")}
            {text("company_reg_no", "settings.companyRegNo")}
            {text("peppol_scheme", "settings.peppolScheme", { placeholder: "0088" })}
            {text("peppol_id", "settings.peppolId")}
            {text("iban", "settings.iban")}
            {text("bic", "settings.bic")}
          </div>
        </section>
        {perms.isAdmin && (
          <div className="row" style={{ marginTop: 16 }}>
            <Button type="submit" variant="primary" busy={save.isPending}>
              {t("app.save")}
            </Button>
            {err && !Object.keys(err.fields).length && <span className="field-error">{err.message}</span>}
          </div>
        )}
      </form>
      <AccountDataSection />
    </div>
  );
}
