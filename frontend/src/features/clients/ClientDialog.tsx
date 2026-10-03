// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, Dialog, DialogActions, SelectField, TextAreaField, TextField, useToast } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { CURRENCIES } from "../../lib/reference";
import { clientKeys, clientsQuery, useAccountDefaults, type Client } from "../projects/queries";
import { peppolQuery } from "../invoices/PeppolSettings";
import { ContactsSection } from "./ContactsSection";

type TextKey = Exclude<keyof Schemas["ClientInput"], "is_active">;
type ClientForm = Record<TextKey, string> & { is_active: boolean };

const TEXT_KEYS: TextKey[] = [
  "name",
  "currency",
  "address_line1",
  "address_line2",
  "postal_code",
  "city",
  "region",
  "country_code",
  "vat_id",
  "company_reg_no",
  "peppol_scheme",
  "peppol_id",
  "notes",
];

function toForm(c: Client | undefined, currency: string): ClientForm {
  const f = Object.fromEntries(TEXT_KEYS.map((k) => [k, (c?.[k] as string | undefined) ?? ""])) as Record<TextKey, string>;
  return { ...f, currency: c?.currency ?? currency, is_active: c?.is_active ?? true };
}

/**
 * Create or edit a client. After creating, the dialog stays open on the new client so contacts can be added.
 * `clientId` is null when closed and "new" when creating.
 */
export function ClientDialog({ clientId, onClose, onCreated }: { clientId: string | null; onClose: () => void; onCreated: (id: string) => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const defaults = useAccountDefaults();
  const formId = useId();
  const open = clientId !== null;
  const isNew = clientId === "new";
  const { data } = useQuery(clientsQuery);
  const client = isNew ? undefined : data?.data.find((c) => c.id === clientId);
  const [form, setForm] = useState<ClientForm>(() => toForm(undefined, defaults.currency));

  const save = useMutation({
    mutationFn: (f: ClientForm) => {
      const text = Object.fromEntries(TEXT_KEYS.map((k) => [k, k === "country_code" ? f[k].trim().toUpperCase() : f[k].trim()]));
      if (isNew) {
        // Leave blanks out on create; on update a blank clears the field.
        const body = Object.fromEntries(Object.entries(text).filter(([, v]) => v !== "")) as Schemas["ClientInput"];
        return unwrap(api.POST("/api/v1/clients", { body }));
      }
      return unwrap(api.PATCH("/api/v1/clients/{id}", { params: { path: { id: clientId! } }, body: { ...text, is_active: f.is_active } }));
    },
    onSuccess: (c) => {
      qc.setQueryData(clientKeys.list, (old: { data: Client[]; next_cursor?: string } | undefined) =>
        old ? { ...old, data: isNew ? [...old.data, c] : old.data.map((x) => (x.id === c.id ? c : x)) } : old,
      );
      void qc.invalidateQueries({ queryKey: clientKeys.all });
      // Project views embed the client's name and currency.
      if (!isNew) void qc.invalidateQueries({ queryKey: ["projects"] });
      if (isNew) {
        toast(t("clients.created"));
        onCreated(c.id);
      } else {
        toast(t("app.saved"));
        onClose();
      }
    },
  });

  // Load the form each time the dialog opens or switches client.
  useEffect(() => {
    if (!open) return;
    setForm(toForm(client, defaults.currency));
    save.reset();
    // Only when opening or switching client, not on every cache refresh.
  }, [open, clientId]); // eslint-disable-line react-hooks/exhaustive-deps

  const err = save.error ? errorInfo(save.error) : null;
  const set = (k: keyof ClientForm) => (v: string | boolean) => setForm((f) => ({ ...f, [k]: v }));
  const text = (k: TextKey, label: string, extra: Partial<Parameters<typeof TextField>[0]> = {}) => (
    <TextField label={label} value={form[k]} onChange={set(k)} error={err?.fields[k]} autoComplete="off" {...extra} />
  );
  const currencies = CURRENCIES.includes(form.currency) ? CURRENCIES : [form.currency, ...CURRENCIES];

  const submit = (e: FormEvent) => {
    e.preventDefault();
    e.stopPropagation();
    save.mutate(form);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => !o && onClose()}
      title={isNew ? t("clients.newClient") : (client?.name ?? t("clients.editClient"))}
      description={isNew ? t("clients.dialogLead") : undefined}
      wide
    >
      <form id={formId} onSubmit={submit} className="stack" noValidate>
        <div className="form-grid">
          {text("name", t("clients.fields.name"), { required: true, autoFocus: true })}
          <SelectField
            label={t("clients.fields.currency")}
            hint={t("clients.fields.currencyHint")}
            value={form.currency}
            onChange={set("currency")}
            options={currencies.map((c) => ({ value: c, label: c }))}
            error={err?.fields.currency}
          />
        </div>

        <section className="form-section">
          <h3 style={{ marginBottom: 12 }}>{t("clients.sections.address")}</h3>
          <div className="form-grid">
            {text("address_line1", t("clients.fields.addressLine1"), { fieldClassName: "span-2" })}
            {text("address_line2", t("clients.fields.addressLine2"), { fieldClassName: "span-2" })}
            {text("postal_code", t("clients.fields.postalCode"))}
            {text("city", t("clients.fields.city"))}
            {text("region", t("clients.fields.region"))}
            {text("country_code", t("clients.fields.countryCode"), { maxLength: 2, hint: t("clients.fields.countryCodeHint"), style: { textTransform: "uppercase" } })}
          </div>
        </section>

        <section className="form-section">
          <h3>{t("clients.sections.tax")}</h3>
          <p className="muted" style={{ margin: "4px 0 12px" }}>
            {t("clients.sections.taxLead")}
          </p>
          <div className="form-grid">
            {text("vat_id", t("clients.fields.vatId"))}
            {text("company_reg_no", t("clients.fields.companyRegNo"))}
            {text("peppol_scheme", t("clients.fields.peppolScheme"), { placeholder: "0088", hint: t("clients.fields.peppolSchemeHint") })}
            {text("peppol_id", t("clients.fields.peppolId"))}
          </div>
          {!isNew && client?.peppol_id && <PeppolReachability client={client} />}
        </section>

        <section className="form-section">
          <div className="stack">
            <TextAreaField label={t("clients.fields.notes")} hint={t("clients.fields.notesHint")} value={form.notes} onChange={set("notes")} rows={3} error={err?.fields.notes} />
            {!isNew && (
              <Checkbox checked={form.is_active} onChange={set("is_active")} label={t("clients.fields.active")} hint={t("clients.fields.activeHint")} />
            )}
          </div>
        </section>

        {err && !Object.keys(err.fields).length && (
          <p className="notice notice-error" role="alert">
            {err.message}
          </p>
        )}
      </form>

      <section className="form-section" style={{ marginTop: 24, paddingTop: 24, borderTop: "1px solid var(--rule)" }}>
        {client ? <ContactsSection client={client} /> : <p className="muted">{t("clients.contacts.afterCreate")}</p>}
      </section>

      <DialogActions>
        <Button onClick={onClose}>{isNew ? t("app.cancel") : t("app.close")}</Button>
        <Button type="submit" form={formId} variant="primary" busy={save.isPending}>
          {isNew ? t("clients.create") : t("app.save")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** "Reachable via Peppol", as the provider last said, with a button to ask again. */
function PeppolReachability({ client }: { client: Client }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const peppol = useQuery({ ...peppolQuery, retry: false });
  const check = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/clients/{id}/peppol_check", { params: { path: { id: client.id } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: clientKeys.all }),
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  if (!peppol.data?.connected) return null;
  const reachable = check.data?.reachable ?? client.peppol_reachable;
  return (
    <p className="row" style={{ marginTop: 12, gap: 12 }}>
      {reachable === true ? (
        <span className="badge badge-ok">{t("clients.peppol.reachable")}</span>
      ) : reachable === false ? (
        <span className="badge badge-warn">{t("clients.peppol.unreachable")}</span>
      ) : (
        <span className="muted small">{t("clients.peppol.unchecked")}</span>
      )}
      <Button size="sm" onClick={() => check.mutate()} busy={check.isPending}>
        {t("clients.peppol.check")}
      </Button>
    </p>
  );
}
