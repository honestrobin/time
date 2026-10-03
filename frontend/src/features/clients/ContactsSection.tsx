// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, TextField, useToast } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { clientKeys, type Client, type Contact } from "../projects/queries";

interface ContactForm {
  name: string;
  title: string;
  email: string;
  phone: string;
  is_invoice_recipient: boolean;
}

const emptyContact = (first: boolean): ContactForm => ({ name: "", title: "", email: "", phone: "", is_invoice_recipient: first });

const toForm = (c: Contact): ContactForm => ({
  name: c.name,
  title: c.title ?? "",
  email: c.email ?? "",
  phone: c.phone ?? "",
  is_invoice_recipient: c.is_invoice_recipient,
});

/** The people at a client, and which of them receive invoices. Changes save immediately. */
export function ContactsSection({ client }: { client: Client }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  // null: no form; "new": adding; otherwise the id of the contact being edited.
  const [editing, setEditing] = useState<string | null>(null);
  const [form, setForm] = useState<ContactForm>(emptyContact(false));

  const refresh = () => void qc.invalidateQueries({ queryKey: clientKeys.all });

  const save = useMutation({
    mutationFn: (f: ContactForm) => {
      const body = { name: f.name.trim(), title: f.title.trim(), email: f.email.trim(), phone: f.phone.trim(), is_invoice_recipient: f.is_invoice_recipient };
      return editing === "new"
        ? unwrap(api.POST("/api/v1/clients/{id}/contacts", { params: { path: { id: client.id } }, body }))
        : unwrap(api.PATCH("/api/v1/clients/{id}/contacts/{contactId}", { params: { path: { id: client.id, contactId: editing! } }, body }));
    },
    onSuccess: (c) => {
      toast(editing === "new" ? t("clients.contacts.added", { name: c.name }) : t("clients.contacts.saved"));
      setEditing(null);
      refresh();
    },
  });

  const remove = useMutation({
    mutationFn: (c: Contact) => unwrap(api.DELETE("/api/v1/clients/{id}/contacts/{contactId}", { params: { path: { id: client.id, contactId: c.id } } })),
    onSuccess: (_, c) => {
      toast(t("clients.contacts.deleted", { name: c.name }));
      if (editing === c.id) setEditing(null);
      refresh();
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  const startNew = () => {
    save.reset();
    setForm(emptyContact(client.contacts.length === 0));
    setEditing("new");
  };
  const startEdit = (c: Contact) => {
    save.reset();
    setForm(toForm(c));
    setEditing(c.id);
  };

  const err = save.error ? errorInfo(save.error) : null;
  const set = (k: keyof ContactForm) => (v: string | boolean) => setForm((f) => ({ ...f, [k]: v }));
  const submit = (e: FormEvent) => {
    e.preventDefault();
    e.stopPropagation();
    save.mutate(form);
  };

  const contactForm = (
    <form className="contact-form stack" onSubmit={submit} noValidate>
      <h4 style={{ margin: 0 }}>{editing === "new" ? t("clients.contacts.newTitle") : t("clients.contacts.editTitle")}</h4>
      <div className="form-grid">
        <TextField label={t("clients.contacts.name")} value={form.name} onChange={set("name")} error={err?.fields.name} required autoFocus autoComplete="off" />
        <TextField label={t("clients.contacts.titleField")} hint={t("clients.contacts.titleHint")} value={form.title} onChange={set("title")} error={err?.fields.title} autoComplete="off" />
        <TextField label={t("clients.contacts.email")} type="email" value={form.email} onChange={set("email")} error={err?.fields.email} autoComplete="off" />
        <TextField label={t("clients.contacts.phone")} type="tel" value={form.phone} onChange={set("phone")} error={err?.fields.phone} autoComplete="off" />
      </div>
      <Checkbox
        checked={form.is_invoice_recipient}
        onChange={set("is_invoice_recipient")}
        label={t("clients.contacts.invoiceRecipient")}
        hint={t("clients.contacts.invoiceRecipientHint")}
      />
      {err && !Object.keys(err.fields).length && (
        <p className="notice notice-error" role="alert">
          {err.message}
        </p>
      )}
      <div className="row">
        <Button type="submit" variant="primary" size="sm" busy={save.isPending}>
          {editing === "new" ? t("clients.contacts.add") : t("clients.contacts.save")}
        </Button>
        <Button size="sm" onClick={() => setEditing(null)}>
          {t("app.cancel")}
        </Button>
      </div>
    </form>
  );

  return (
    <div className="stack" style={{ gap: 12 }}>
      <div className="section-head" style={{ marginBottom: 0 }}>
        <h3>{t("clients.contacts.title")}</h3>
        <span className="spacer" />
        {editing === null && (
          <Button size="sm" onClick={startNew}>
            {t("clients.contacts.addButton")}
          </Button>
        )}
      </div>
      {client.contacts.length === 0 && editing !== "new" && <p className="muted">{t("clients.contacts.empty")}</p>}
      {client.contacts.length > 0 && (
        <table className="ledger">
          <thead>
            <tr>
              <th>{t("clients.contacts.name")}</th>
              <th>{t("clients.contacts.email")}</th>
              <th>{t("clients.contacts.phone")}</th>
              <th>{t("clients.contacts.invoices")}</th>
              <th>
                <span className="sr-only">{t("clients.list.actions")}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {client.contacts.map((c) =>
              editing === c.id ? (
                <tr key={c.id}>
                  <td colSpan={5} style={{ padding: 8 }}>
                    {contactForm}
                  </td>
                </tr>
              ) : (
                <tr key={c.id}>
                  <td>
                    <strong style={{ fontWeight: 600 }}>{c.name}</strong>
                    {c.title && <div className="muted" style={{ fontSize: "var(--text-sm)" }}>{c.title}</div>}
                  </td>
                  <td>{c.email ? <a href={`mailto:${c.email}`}>{c.email}</a> : ""}</td>
                  <td>{c.phone ?? ""}</td>
                  <td>{c.is_invoice_recipient && <span className="badge badge-ok">{t("clients.contacts.receivesInvoices")}</span>}</td>
                  <td className="actions">
                    <Button size="sm" variant="ghost" onClick={() => startEdit(c)} aria-label={t("clients.contacts.editFor", { name: c.name })}>
                      {t("app.edit")}
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => remove.mutate(c)}
                      busy={remove.isPending && remove.variables?.id === c.id}
                      aria-label={t("clients.contacts.deleteFor", { name: c.name })}
                    >
                      {t("app.delete")}
                    </Button>
                  </td>
                </tr>
              ),
            )}
          </tbody>
        </table>
      )}
      {editing === "new" && contactForm}
      {client.contacts.length > 0 && !client.contacts.some((c) => c.is_invoice_recipient) && (
        <p className="notice notice-warn">{t("clients.contacts.noRecipient")}</p>
      )}
    </div>
  );
}
