// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, PageHeader, SelectField, TextAreaField, TextField, useToast } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { usePermissions } from "../../lib/session";
import { PeppolSettings } from "./PeppolSettings";
import { invoiceSettingsQuery } from "./queries";
import "./invoices.css";

type Settings = Schemas["InvoiceSettingsView"];

interface Form {
  paymentTermsDays: string;
  numberPrefix: string;
  nextNumber: string;
  numberPadding: string;
  numberPerYear: boolean;
  defaultTax1Name: string;
  defaultTax1Percent: string;
  defaultTax2Name: string;
  defaultTax2Percent: string;
  paymentInstructions: string;
  notes: string;
  footer: string;
  remindersEnabled: boolean;
  reminderDays: string;
  bccSender: boolean;
  hybridPdf: "auto" | "on" | "off";
  contactName: string;
  contactEmail: string;
  contactPhone: string;
}

const s = (v: unknown) => (v === null || v === undefined ? "" : String(v));
const n = (v: string) => (v.trim() === "" ? null : Number(v.replace(",", ".")));

function toForm(x: Settings): Form {
  return {
    paymentTermsDays: s(x.payment_terms_days),
    numberPrefix: x.number_prefix,
    nextNumber: s(x.next_number),
    numberPadding: s(x.number_padding),
    numberPerYear: x.number_per_year,
    defaultTax1Name: x.default_tax1_name ?? "",
    defaultTax1Percent: s(x.default_tax1_percent),
    defaultTax2Name: x.default_tax2_name ?? "",
    defaultTax2Percent: s(x.default_tax2_percent),
    paymentInstructions: x.payment_instructions ?? "",
    notes: x.notes ?? "",
    footer: x.footer ?? "",
    remindersEnabled: x.reminders_enabled,
    reminderDays: x.reminder_days.join(", "),
    bccSender: x.bcc_sender,
    hybridPdf: x.einvoice_hybrid_pdf == null ? "auto" : x.einvoice_hybrid_pdf ? "on" : "off",
    contactName: x.invoice_contact_name ?? "",
    contactEmail: x.invoice_contact_email ?? "",
    contactPhone: x.invoice_contact_phone ?? "",
  };
}

/** Defaults for new invoices: numbering, payment terms, taxes, payment details, reminders. */
export function InvoiceSettingsPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const perms = usePermissions();
  const q = useQuery(invoiceSettingsQuery);
  const [form, setForm] = useState<Form | null>(null);
  useEffect(() => {
    if (q.data && !form) setForm(toForm(q.data));
  }, [q.data, form]);
  const save = useMutation({
    mutationFn: (f: Form) =>
      unwrap(
        api.PATCH("/api/v1/invoice_settings", {
          body: {
            payment_terms_days: n(f.paymentTermsDays),
            number_prefix: f.numberPrefix,
            next_number: n(f.nextNumber),
            number_padding: n(f.numberPadding),
            number_per_year: f.numberPerYear,
            default_tax1_name: f.defaultTax1Name || null,
            default_tax1_percent: n(f.defaultTax1Percent),
            default_tax2_name: f.defaultTax2Name || null,
            default_tax2_percent: n(f.defaultTax2Percent),
            payment_instructions: f.paymentInstructions || null,
            notes: f.notes || null,
            footer: f.footer || null,
            reminders_enabled: f.remindersEnabled,
            reminder_days: f.reminderDays.split(/[,\s]+/).filter(Boolean).map(Number),
            bcc_sender: f.bccSender,
            einvoice_hybrid_pdf: f.hybridPdf === "auto" ? null : f.hybridPdf === "on",
            invoice_contact_name: f.contactName || null,
            invoice_contact_email: f.contactEmail || null,
            invoice_contact_phone: f.contactPhone || null,
          } as never,
        }),
      ),
    onSuccess: (x) => {
      qc.setQueryData(invoiceSettingsQuery.queryKey, x);
      setForm(toForm(x));
      toast(t("app.saved"));
    },
  });
  if (!form || !q.data) return <div className="page">{t("app.loading")}</div>;
  const err = save.error ? errorInfo(save.error) : null;
  const set = (k: keyof Form) => (v: string | boolean) => setForm((f) => (f ? { ...f, [k]: v } : f));
  const readOnly = !perms.isAdmin;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate(form);
  };
  return (
    <div className="page page-narrow">
      <PageHeader title={t("invoiceSettings.title")} lead={t("invoiceSettings.lead")} />
      {readOnly && <p className="notice">{t("settings.adminOnly")}</p>}
      <form className="stack" onSubmit={submit}>
        <section className="form-section stack">
          <h2>{t("invoiceSettings.numbering")}</h2>
          <p className="muted lead">{t("invoiceSettings.numberingLead")}</p>
          <div className="form-grid">
            <TextField label={t("invoiceSettings.prefix")} hint={t("invoiceSettings.prefixHint")} value={form.numberPrefix} onChange={set("numberPrefix")} error={err?.fields.number_prefix} disabled={readOnly} />
            <TextField label={t("invoiceSettings.nextNumber")} inputMode="numeric" value={form.nextNumber} onChange={set("nextNumber")} error={err?.fields.next_number} disabled={readOnly} />
            <TextField label={t("invoiceSettings.padding")} hint={t("invoiceSettings.paddingHint")} inputMode="numeric" value={form.numberPadding} onChange={set("numberPadding")} error={err?.fields.number_padding} disabled={readOnly} />
            <div className="field">
              <span className="field-label">{t("invoiceSettings.preview")}</span>
              <p className="number-preview">{q.data.next_number_preview}</p>
            </div>
          </div>
          <Checkbox checked={form.numberPerYear} onChange={set("numberPerYear")} label={t("invoiceSettings.perYear")} hint={t("invoiceSettings.perYearHint")} disabled={readOnly} />
        </section>

        <section className="form-section stack">
          <h2>{t("invoiceSettings.defaults")}</h2>
          <div className="form-grid">
            <TextField label={t("invoiceSettings.terms")} hint={t("invoiceSettings.termsHint")} inputMode="numeric" value={form.paymentTermsDays} onChange={set("paymentTermsDays")} error={err?.fields.payment_terms_days} disabled={readOnly} />
            <div />
            <TextField label={t("invoices.fields.tax1Name")} placeholder={t("invoices.fields.taxNamePlaceholder")} value={form.defaultTax1Name} onChange={set("defaultTax1Name")} error={err?.fields.default_tax1_name} disabled={readOnly} />
            <TextField label={t("invoices.fields.taxPercent")} inputMode="decimal" value={form.defaultTax1Percent} onChange={set("defaultTax1Percent")} error={err?.fields.default_tax1_percent} disabled={readOnly} />
            <TextField label={t("invoices.fields.tax2Name")} value={form.defaultTax2Name} onChange={set("defaultTax2Name")} error={err?.fields.default_tax2_name} disabled={readOnly} />
            <TextField label={t("invoices.fields.taxPercent")} inputMode="decimal" value={form.defaultTax2Percent} onChange={set("defaultTax2Percent")} error={err?.fields.default_tax2_percent} disabled={readOnly} />
          </div>
          <TextAreaField label={t("invoices.fields.paymentInstructions")} hint={t("invoices.fields.paymentInstructionsHint")} value={form.paymentInstructions} onChange={set("paymentInstructions")} rows={3} disabled={readOnly} />
          <TextAreaField label={t("invoices.fields.notes")} hint={t("invoiceSettings.notesHint")} value={form.notes} onChange={set("notes")} rows={2} disabled={readOnly} />
          <TextField label={t("invoices.fields.footer")} hint={t("invoiceSettings.footerHint")} value={form.footer} onChange={set("footer")} disabled={readOnly} />
        </section>

        <section className="form-section stack">
          <h2>{t("invoiceSettings.sending")}</h2>
          <Checkbox checked={form.bccSender} onChange={set("bccSender")} label={t("invoiceSettings.bcc")} hint={t("invoiceSettings.bccHint")} disabled={readOnly} />
          <Checkbox checked={form.remindersEnabled} onChange={set("remindersEnabled")} label={t("invoiceSettings.reminders")} hint={t("invoiceSettings.remindersHint")} disabled={readOnly} />
          {form.remindersEnabled && (
            <TextField label={t("invoiceSettings.reminderDays")} hint={t("invoiceSettings.reminderDaysHint")} value={form.reminderDays} onChange={set("reminderDays")} error={err?.fields.reminder_days} disabled={readOnly} />
          )}
        </section>

        <section className="form-section stack">
          <h2>{t("invoiceSettings.einvoices")}</h2>
          <p className="muted">{t("invoiceSettings.einvoicesLead")}</p>
          <SelectField
            label={t("invoiceSettings.hybridPdf")}
            hint={q.data.einvoice_hybrid_pdf_active ? t("invoiceSettings.hybridActive") : t("invoiceSettings.hybridInactive")}
            value={form.hybridPdf}
            onChange={(v) => set("hybridPdf")(v)}
            options={(["auto", "on", "off"] as const).map((v) => ({ value: v, label: t(`invoiceSettings.hybrid.${v}`) }))}
            disabled={readOnly}
          />
          <p className="muted small">{t("invoiceSettings.contactLead")}</p>
          <div className="form-grid">
            <TextField label={t("invoiceSettings.contactName")} value={form.contactName} onChange={set("contactName")} disabled={readOnly} />
            <TextField label={t("invoiceSettings.contactEmail")} type="email" value={form.contactEmail} onChange={set("contactEmail")} error={err?.fields.invoice_contact_email} disabled={readOnly} />
            <TextField label={t("invoiceSettings.contactPhone")} value={form.contactPhone} onChange={set("contactPhone")} disabled={readOnly} />
          </div>
        </section>

        {!readOnly && (
          <div className="form-actions">
            <Button type="submit" variant="primary" busy={save.isPending}>
              {t("app.save")}
            </Button>
            {err && !Object.keys(err.fields).length && <span className="field-error">{err.message}</span>}
          </div>
        )}
      </form>
      <PeppolSettings readOnly={readOnly} />
    </div>
  );
}
