// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, ConfirmDialog, Dialog, DialogActions, Menu, MoneyField, MoneyInput, SelectField, TextAreaField, TextField, useToast } from "../../design";
import { api, errorInfo, openFile, unwrap } from "../../lib/api";
import { formatDate, formatDateTime, formatMoney } from "../../lib/format";
import { useAuthConfig } from "../../lib/session";
import { invoiceTotals } from "../../lib/invoiceMath";
import { displayState, invoiceKeys, invoiceQuery, stateBadge, type Invoice } from "./queries";
import { AccountingStatus } from "./AccountingStatus";
import { EInvoicePanel } from "./EInvoicePanel";
import "./invoices.css";

interface LineDraft {
  key: string;
  id?: string;
  kind: string;
  description: string;
  quantity: string;
  unitPrice: number | null;
  tax1: boolean;
  tax2: boolean;
  vatCategory: string;
  vatPercent: string;
  timeEntryCount: number;
  expenseCount: number;
}

interface Draft {
  issueDate: string;
  dueDate: string;
  subject: string;
  purchaseOrder: string;
  buyerReference: string;
  taxMode: "named" | "vat";
  tax1Name: string;
  tax1Percent: string;
  tax2Name: string;
  tax2Percent: string;
  vatMode: string;
  exemptionReason: string;
  discountPercent: string;
  notes: string;
  paymentInstructions: string;
  footer: string;
  lines: LineDraft[];
}

const num = (s: string): number | null => {
  const v = s.trim().replace(",", ".");
  if (v === "") return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
};
const str = (v: unknown) => (v === null || v === undefined ? "" : String(v));
let keySeq = 0;
const newKey = () => `new-${++keySeq}`;

function toDraft(i: Invoice): Draft {
  return {
    issueDate: i.issue_date,
    dueDate: i.due_date ?? "",
    subject: i.subject ?? "",
    purchaseOrder: i.purchase_order ?? "",
    buyerReference: i.buyer_reference ?? "",
    taxMode: i.vat_mode ? "vat" : "named",
    tax1Name: i.tax1_name ?? "",
    tax1Percent: str(i.tax1_percent),
    tax2Name: i.tax2_name ?? "",
    tax2Percent: str(i.tax2_percent),
    vatMode: i.vat_mode ?? "standard",
    exemptionReason: i.exemption_reason ?? "",
    discountPercent: str(i.discount_percent),
    notes: i.notes ?? "",
    paymentInstructions: i.payment_instructions ?? "",
    footer: i.footer ?? "",
    lines: i.lines.map((l) => ({
      key: l.id,
      id: l.id,
      kind: l.kind,
      description: l.description ?? "",
      quantity: str(Number(l.quantity)),
      unitPrice: l.unit_price,
      tax1: l.tax1_applies,
      tax2: l.tax2_applies,
      vatCategory: l.vat_category_code ?? "S",
      vatPercent: str(l.vat_percent === null || l.vat_percent === undefined ? "" : Number(l.vat_percent)),
      timeEntryCount: l.time_entry_count,
      expenseCount: l.expense_count,
    })),
  };
}

function toBody(d: Draft) {
  const vat = d.taxMode === "vat";
  return {
    issue_date: d.issueDate,
    due_date: d.dueDate || null,
    subject: d.subject || null,
    purchase_order: d.purchaseOrder || null,
    buyer_reference: d.buyerReference || null,
    tax1_name: d.tax1Name || null,
    tax1_percent: num(d.tax1Percent),
    tax2_name: vat ? null : d.tax2Name || null,
    tax2_percent: vat ? null : num(d.tax2Percent),
    vat_mode: vat ? d.vatMode : null,
    exemption_reason: vat && d.vatMode === "exempt" ? d.exemptionReason || null : null,
    discount_percent: num(d.discountPercent),
    notes: d.notes || null,
    payment_instructions: d.paymentInstructions || null,
    footer: d.footer || null,
    lines: d.lines.map((l) => ({
      id: l.id,
      kind: l.kind,
      description: l.description,
      quantity: num(l.quantity) ?? 0,
      unit_price: l.unitPrice ?? 0,
      tax1_applies: l.tax1,
      tax2_applies: l.tax2,
      vat_category_code: vat ? l.vatCategory : null,
      vat_percent: vat ? num(l.vatPercent) : null,
    })),
  };
}

const VAT_CATEGORIES = ["S", "Z", "E", "AE", "K", "G", "O"];

/** One invoice: edit it while it is a draft or unpaid, send it, record payments, void it. */
export function InvoicePage() {
  const { invoiceId } = useParams({ strict: false }) as { invoiceId: string };
  const { t } = useTranslation();
  const q = useQuery(invoiceQuery(invoiceId));
  if (q.error) return <div className="page"><p className="notice notice-error">{errorInfo(q.error).message}</p></div>;
  if (!q.data) return <div className="page">{t("app.loading")}</div>;
  return <InvoiceEditor key={q.data.updated_at} invoice={q.data} />;
}

function InvoiceEditor({ invoice }: { invoice: Invoice }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const server = useMemo(() => toDraft(invoice), [invoice]);
  const [draft, setDraft] = useState<Draft>(server);
  const dirty = JSON.stringify(draft) !== JSON.stringify(server);
  const editable = !invoice.is_read_only && invoice.state !== "paid" && invoice.state !== "void";
  const state = displayState(invoice);
  const currency = invoice.currency;
  const [dialog, setDialog] = useState<null | "send" | "payment" | "void" | "delete">(null);

  const set = <K extends keyof Draft>(k: K) => (v: Draft[K]) => setDraft((d) => ({ ...d, [k]: v }));
  const setLine = (key: string, patch: Partial<LineDraft>) => setDraft((d) => ({ ...d, lines: d.lines.map((l) => (l.key === key ? { ...l, ...patch } : l)) }));

  const refresh = (inv?: Invoice) => {
    if (inv) qc.setQueryData(invoiceKeys.one(inv.id), inv);
    void qc.invalidateQueries({ queryKey: invoiceKeys.all });
  };
  const save = useMutation({
    mutationFn: () => unwrap(api.PATCH("/api/v1/invoices/{id}", { params: { path: { id: invoice.id } }, body: toBody(draft) as never })),
    onSuccess: (inv) => {
      refresh(inv);
      toast(t("invoices.saved"));
    },
  });
  const markSent = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/invoices/{id}/mark_sent", { params: { path: { id: invoice.id } } })),
    onSuccess: (inv) => {
      refresh(inv);
      toast(t("invoices.markedSent", { number: inv.number }));
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const voidIt = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/invoices/{id}/void", { params: { path: { id: invoice.id } } })),
    onSuccess: (inv) => {
      setDialog(null);
      refresh(inv);
      toast(t("invoices.voided"));
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/invoices/{id}", { params: { path: { id: invoice.id } } })),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: invoiceKeys.all });
      toast(t("invoices.deleted"));
      await navigate({ to: "/invoices" });
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });
  const deletePayment = useMutation({
    mutationFn: (paymentId: string) => unwrap(api.DELETE("/api/v1/invoices/{id}/payments/{paymentId}", { params: { path: { id: invoice.id, paymentId } } })),
    onSuccess: (inv) => refresh(inv),
  });

  const err = save.error ? errorInfo(save.error) : null;
  const vat = draft.taxMode === "vat";
  const totals = invoiceTotals(
    draft.lines.map((l) => ({ quantity: num(l.quantity) ?? 0, unitPrice: l.unitPrice ?? 0, tax1: l.tax1, tax2: l.tax2, vatCategory: l.vatCategory, vatPercent: num(l.vatPercent) })),
    num(draft.discountPercent),
    num(draft.tax1Percent),
    vat ? null : num(draft.tax2Percent),
    vat,
  );
  const money = (m: number) => formatMoney(m, currency);
  const pdf = () => openFile(`/api/v1/invoices/${invoice.id}/pdf`).catch((e) => toast(errorInfo(e).message, "error"));
  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(invoice.public_url!);
      toast(t("invoices.linkCopied"));
    } catch {
      window.prompt(t("invoices.copyLink"), invoice.public_url!);
    }
  };

  const addLine = () =>
    setDraft((d) => ({
      ...d,
      lines: [
        ...d.lines,
        { key: newKey(), kind: "service", description: "", quantity: "1", unitPrice: null, tax1: !!d.tax1Percent, tax2: !!d.tax2Percent, vatCategory: d.vatMode === "reverse_charge" ? "AE" : d.vatMode === "exempt" ? "E" : d.vatMode === "outside_scope" ? "O" : "S", vatPercent: d.tax1Percent, timeEntryCount: 0, expenseCount: 0 },
      ],
    }));

  // Leaving with unsaved edits loses them; ask the browser to warn.
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate();
  };

  const menuItems = [
    { label: invoice.number ? t("invoices.downloadPdf") : t("invoices.previewPdf"), onSelect: () => void pdf() },
    ...(invoice.public_url ? [{ label: t("invoices.copyLink"), onSelect: () => void copyLink() }] : []),
    ...(invoice.number && invoice.state !== "void" && invoice.paid === 0 && !invoice.is_read_only ? [{ label: t("invoices.void"), onSelect: () => setDialog("void"), danger: true }] : []),
    ...(!invoice.number && !invoice.is_read_only ? [{ label: t("invoices.delete"), onSelect: () => setDialog("delete"), danger: true }] : []),
  ];

  return (
    <div className="page invoice-page">
      <p style={{ marginBottom: 8 }}>
        <Link to="/invoices">{t("invoices.backToList")}</Link>
      </p>
      <header className="page-header">
        <div>
          <h1>{invoice.number ? t("invoices.titleNumber", { number: invoice.number }) : t("invoices.draftTitle")}</h1>
          <p className="muted">
            {invoice.client.name} · <span className={stateBadge(state)}>{t(`invoices.state.${state}`)}</span>
            {invoice.sent_at && ` · ${t("invoices.sentOn", { date: formatDate(invoice.sent_at.slice(0, 10)) })}`}
            {invoice.view_count > 0 && ` · ${t("invoices.viewed", { count: invoice.view_count })}`}
          </p>
        </div>
        <div className="actions">
          {editable && invoice.state === "draft" && (
            <>
              <Button onClick={() => markSent.mutate()} busy={markSent.isPending} disabled={dirty} title={dirty ? t("invoices.saveFirst") : undefined}>
                {t("invoices.markSent")}
              </Button>
              <Button variant="primary" onClick={() => setDialog("send")} disabled={dirty} title={dirty ? t("invoices.saveFirst") : undefined}>
                {t("invoices.send")}
              </Button>
            </>
          )}
          {editable && invoice.state !== "draft" && (
            <>
              <Button onClick={() => setDialog("send")} disabled={dirty}>
                {t("invoices.sendAgain")}
              </Button>
              <Button variant="primary" onClick={() => setDialog("payment")} disabled={dirty}>
                {t("invoices.recordPayment")}
              </Button>
            </>
          )}
          <Menu trigger={<Button aria-label={t("invoices.more")}>⋯</Button>} items={menuItems} />
        </div>
      </header>

      {invoice.is_read_only && <p className="notice">{t("invoices.readOnly")}</p>}
      {invoice.state === "paid" && <p className="notice notice-ok">{t("invoices.paidNotice", { date: invoice.paid_at ? formatDate(invoice.paid_at.slice(0, 10)) : "" })}</p>}
      {invoice.state === "void" && <p className="notice">{t("invoices.voidNotice")}</p>}
      {editable && invoice.state !== "draft" && invoice.sent_at && (
        <p className="notice">{t("invoices.sentNotice", { date: formatDate(invoice.sent_at.slice(0, 10)) })}</p>
      )}

      <form onSubmit={submit} className="stack invoice-form">
        <section className="form-grid invoice-header-grid">
          <TextField type="date" label={t("invoices.fields.issueDate")} value={draft.issueDate} onChange={set("issueDate")} disabled={!editable} error={err?.fields.issue_date} />
          <TextField type="date" label={t("invoices.fields.dueDate")} value={draft.dueDate} onChange={set("dueDate")} disabled={!editable} error={err?.fields.due_date} />
          <TextField label={t("invoices.fields.subject")} value={draft.subject} onChange={set("subject")} disabled={!editable} fieldClassName="span-2" />
          <TextField label={t("invoices.fields.purchaseOrder")} value={draft.purchaseOrder} onChange={set("purchaseOrder")} disabled={!editable} />
          <TextField label={t("invoices.fields.buyerReference")} hint={t("invoices.fields.buyerReferenceHint")} value={draft.buyerReference} onChange={set("buyerReference")} disabled={!editable} />
        </section>

        <section>
          <div className="table-scroll">
            <table className="ledger invoice-lines">
              <thead>
                <tr>
                  <th>{t("invoices.col.description")}</th>
                  <th className="num qty">{t("invoices.col.quantity")}</th>
                  <th className="num price">{t("invoices.col.unitPrice")}</th>
                  {vat ? (
                    <th className="vat">{t("invoices.col.vat")}</th>
                  ) : (
                    <>
                      {draft.tax1Percent && <th className="tax">{draft.tax1Name || t("invoices.tax")}</th>}
                      {draft.tax2Percent && <th className="tax">{draft.tax2Name || t("invoices.tax2")}</th>}
                    </>
                  )}
                  <th className="num">{t("invoices.col.amount")}</th>
                  {editable && <th className="col-narrow"><span className="sr-only">{t("invoices.col.actions")}</span></th>}
                </tr>
              </thead>
              <tbody>
                {draft.lines.length === 0 && (
                  <tr>
                    <td colSpan={7} className="muted">
                      {t("invoices.noLines")}
                    </td>
                  </tr>
                )}
                {draft.lines.map((l, i) => (
                  <tr key={l.key}>
                    <td>
                      <label className="sr-only" htmlFor={`desc-${l.key}`}>{t("invoices.col.description")}</label>
                      <textarea id={`desc-${l.key}`} className="input" rows={Math.min(4, Math.max(1, l.description.split("\n").length))} value={l.description} onChange={(e) => setLine(l.key, { description: e.target.value })} disabled={!editable} />
                      {(l.timeEntryCount > 0 || l.expenseCount > 0) && (
                        <div className="muted small">
                          {l.timeEntryCount > 0 && t("invoices.linkedTime", { count: l.timeEntryCount })}
                          {l.timeEntryCount > 0 && l.expenseCount > 0 && " · "}
                          {l.expenseCount > 0 && t("invoices.linkedExpenses", { count: l.expenseCount })}
                        </div>
                      )}
                    </td>
                    <td className="num qty">
                      <input className="input input-num" inputMode="decimal" aria-label={t("invoices.col.quantity")} value={l.quantity} onChange={(e) => setLine(l.key, { quantity: e.target.value })} disabled={!editable} />
                    </td>
                    <td className="num price">
                      <MoneyInput value={l.unitPrice} currency={currency} onChange={(v) => setLine(l.key, { unitPrice: v })} disabled={!editable} aria-describedby={undefined} />
                    </td>
                    {vat ? (
                      <td className="vat">
                        <div className="row">
                          <select className="input" aria-label={t("invoices.col.vatCategory")} value={l.vatCategory} onChange={(e) => setLine(l.key, { vatCategory: e.target.value })} disabled={!editable}>
                            {VAT_CATEGORIES.map((c) => (
                              <option key={c} value={c}>{t(`invoices.vatCategory.${c}`)}</option>
                            ))}
                          </select>
                          {l.vatCategory === "S" && (
                            <input className="input input-num rate" inputMode="decimal" aria-label={t("invoices.col.vatRate")} value={l.vatPercent} onChange={(e) => setLine(l.key, { vatPercent: e.target.value })} disabled={!editable} />
                          )}
                        </div>
                      </td>
                    ) : (
                      <>
                        {draft.tax1Percent && (
                          <td className="tax">
                            <input type="checkbox" aria-label={draft.tax1Name || t("invoices.tax")} checked={l.tax1} onChange={(e) => setLine(l.key, { tax1: e.target.checked })} disabled={!editable} />
                          </td>
                        )}
                        {draft.tax2Percent && (
                          <td className="tax">
                            <input type="checkbox" aria-label={draft.tax2Name || t("invoices.tax2")} checked={l.tax2} onChange={(e) => setLine(l.key, { tax2: e.target.checked })} disabled={!editable} />
                          </td>
                        )}
                      </>
                    )}
                    <td className="num">{money(totals.lineTotals[i])}</td>
                    {editable && (
                      <td>
                        <Button size="sm" variant="ghost" aria-label={t("invoices.removeLine")} title={l.timeEntryCount > 0 ? t("invoices.removeLineHint") : undefined} onClick={() => setDraft((d) => ({ ...d, lines: d.lines.filter((x) => x.key !== l.key) }))}>
                          ×
                        </Button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {err?.fields.lines && <p className="field-error">{err.fields.lines}</p>}
          {editable && (
            <Button size="sm" onClick={addLine} style={{ marginTop: 8 }}>
              {t("invoices.addLine")}
            </Button>
          )}
        </section>

        <section className="invoice-bottom">
          <div className="stack invoice-taxes">
            <SelectField
              label={t("invoices.taxMode.label")}
              value={draft.taxMode}
              onChange={(v) => set("taxMode")(v as Draft["taxMode"])}
              options={[
                { value: "named", label: t("invoices.taxMode.named") },
                { value: "vat", label: t("invoices.taxMode.vat") },
              ]}
              disabled={!editable}
            />
            {vat ? (
              <div className="form-grid">
                <SelectField
                  label={t("invoices.fields.vatMode")}
                  value={draft.vatMode}
                  onChange={set("vatMode")}
                  options={["standard", "reverse_charge", "exempt", "outside_scope"].map((v) => ({ value: v, label: t(`invoices.vatMode.${v}`) }))}
                  disabled={!editable}
                  error={err?.fields.vat_mode}
                />
                {draft.vatMode === "standard" && (
                  <TextField label={t("invoices.fields.defaultVatRate")} hint={t("invoices.fields.defaultVatRateHint")} inputMode="decimal" value={draft.tax1Percent} onChange={set("tax1Percent")} disabled={!editable} />
                )}
                {draft.vatMode === "exempt" && (
                  <TextField label={t("invoices.fields.exemptionReason")} value={draft.exemptionReason} onChange={set("exemptionReason")} disabled={!editable} error={err?.fields.exemption_reason} fieldClassName="span-2" />
                )}
              </div>
            ) : (
              <div className="form-grid">
                <TextField label={t("invoices.fields.tax1Name")} placeholder={t("invoices.fields.taxNamePlaceholder")} value={draft.tax1Name} onChange={set("tax1Name")} disabled={!editable} error={err?.fields.tax1_name} />
                <TextField label={t("invoices.fields.taxPercent")} inputMode="decimal" value={draft.tax1Percent} onChange={set("tax1Percent")} disabled={!editable} error={err?.fields.tax1_percent} />
                <TextField label={t("invoices.fields.tax2Name")} value={draft.tax2Name} onChange={set("tax2Name")} disabled={!editable} error={err?.fields.tax2_name} />
                <TextField label={t("invoices.fields.taxPercent")} inputMode="decimal" value={draft.tax2Percent} onChange={set("tax2Percent")} disabled={!editable} error={err?.fields.tax2_percent} />
              </div>
            )}
            <TextField label={t("invoices.fields.discount")} hint={t("invoices.fields.discountHint")} inputMode="decimal" value={draft.discountPercent} onChange={set("discountPercent")} disabled={!editable} error={err?.fields.discount_percent} />
          </div>

          <dl className="invoice-totals">
            <div>
              <dt>{t("invoices.subtotal")}</dt>
              <dd>{money(totals.subtotal)}</dd>
            </div>
            {totals.discount > 0 && (
              <div>
                <dt>{t("invoices.discount", { percent: draft.discountPercent })}</dt>
                <dd>−{money(totals.discount)}</dd>
              </div>
            )}
            {vat ? (
              totals.vat.map((g) => (
                <div key={`${g.category}${g.percent}`}>
                  <dt>{t("invoices.vatLine", { category: t(`invoices.vatCategory.${g.category}`), percent: g.percent })}</dt>
                  <dd>{money(g.tax)}</dd>
                </div>
              ))
            ) : (
              <>
                {num(draft.tax1Percent) !== null && (
                  <div>
                    <dt>{`${draft.tax1Name || t("invoices.tax")} (${draft.tax1Percent} %)`}</dt>
                    <dd>{money(totals.tax1)}</dd>
                  </div>
                )}
                {num(draft.tax2Percent) !== null && (
                  <div>
                    <dt>{`${draft.tax2Name || t("invoices.tax2")} (${draft.tax2Percent} %)`}</dt>
                    <dd>{money(totals.tax2)}</dd>
                  </div>
                )}
              </>
            )}
            <div className="grand">
              <dt>{t("invoices.total")}</dt>
              <dd>{money(totals.total)}</dd>
            </div>
            {invoice.paid > 0 && (
              <>
                <div>
                  <dt>{t("invoices.paid")}</dt>
                  <dd>−{money(invoice.paid)}</dd>
                </div>
                <div className="grand">
                  <dt>{t("invoices.amountDue")}</dt>
                  <dd>{money(Math.max(0, totals.total - invoice.paid))}</dd>
                </div>
              </>
            )}
          </dl>
        </section>

        <section className="form-grid">
          <TextAreaField label={t("invoices.fields.paymentInstructions")} hint={t("invoices.fields.paymentInstructionsHint")} value={draft.paymentInstructions} onChange={set("paymentInstructions")} rows={3} disabled={!editable} />
          <TextAreaField label={t("invoices.fields.notes")} hint={t("invoices.fields.notesHint")} value={draft.notes} onChange={set("notes")} rows={3} disabled={!editable} />
          <TextField label={t("invoices.fields.footer")} value={draft.footer} onChange={set("footer")} disabled={!editable} fieldClassName="span-2" />
        </section>

        {editable && (
          <div className={`form-actions save-bar${dirty ? " is-dirty" : ""}`}>
            <Button type="submit" variant="primary" busy={save.isPending} disabled={!dirty}>
              {t("app.save")}
            </Button>
            <Button variant="ghost" disabled={!dirty} onClick={() => setDraft(server)}>
              {t("invoices.discard")}
            </Button>
            {err && !Object.keys(err.fields).length && <span className="field-error">{err.message}</span>}
          </div>
        )}
      </form>

      {invoice.number && invoice.state !== "void" && <EInvoicePanel invoiceId={invoice.id} clientId={invoice.client.id} />}
      {invoice.number && invoice.state !== "void" && !invoice.is_read_only && <AccountingStatus invoiceId={invoice.id} />}

      {(invoice.payments.length > 0 || invoice.state !== "draft") && (
        <section className="stack" style={{ marginTop: 32 }}>
          <h2>{t("invoices.payments")}</h2>
          {invoice.payments.length === 0 ? (
            <p className="muted">{t("invoices.noPayments")}</p>
          ) : (
            <div className="table-scroll">
              <table className="ledger">
                <thead>
                  <tr>
                    <th>{t("invoices.col.date")}</th>
                    <th className="num">{t("invoices.col.amount")}</th>
                    <th>{t("invoices.col.notes")}</th>
                    <th>{t("invoices.col.recordedBy")}</th>
                    {!invoice.is_read_only && <th className="col-narrow"><span className="sr-only">{t("invoices.col.actions")}</span></th>}
                  </tr>
                </thead>
                <tbody>
                  {invoice.payments.map((p) => (
                    <tr key={p.id}>
                      <td>{formatDate(p.paid_date)}</td>
                      <td className="num">{money(p.amount)}</td>
                      <td>{p.notes}</td>
                      <td className="muted">{p.recorded_by}</td>
                      {!invoice.is_read_only && (
                        <td>
                          <Button size="sm" variant="ghost" onClick={() => deletePayment.mutate(p.id)} aria-label={t("invoices.deletePayment")}>
                            ×
                          </Button>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {invoice.sent_to.length > 0 && <p className="muted small">{t("invoices.sentTo", { to: invoice.sent_to.join(", "), date: invoice.sent_at ? formatDateTime(invoice.sent_at) : "" })}</p>}
        </section>
      )}

      {dialog === "send" && <SendDialog invoice={invoice} onClose={() => setDialog(null)} onSent={refresh} />}
      {dialog === "payment" && <PaymentDialog invoice={invoice} onClose={() => setDialog(null)} onSaved={refresh} />}
      <ConfirmDialog
        open={dialog === "void"}
        onOpenChange={(o) => !o && setDialog(null)}
        title={t("invoices.voidTitle", { number: invoice.number })}
        body={<p>{t("invoices.voidBody")}</p>}
        confirmLabel={t("invoices.void")}
        onConfirm={() => voidIt.mutate()}
        busy={voidIt.isPending}
      />
      <ConfirmDialog
        open={dialog === "delete"}
        onOpenChange={(o) => !o && setDialog(null)}
        title={t("invoices.deleteTitle")}
        body={<p>{t("invoices.deleteBody")}</p>}
        confirmLabel={t("invoices.delete")}
        onConfirm={() => remove.mutate()}
        busy={remove.isPending}
      />
    </div>
  );
}

function SendDialog({ invoice, onClose, onSent }: { invoice: Invoice; onClose: () => void; onSent: (inv: Invoice) => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const defaults = useQuery({ queryKey: ["invoices", invoice.id, "send"], queryFn: () => unwrap(api.GET("/api/v1/invoices/{id}/send", { params: { path: { id: invoice.id } } })) });
  const [to, setTo] = useState<string | null>(null);
  const [subject, setSubject] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [attach, setAttach] = useState(true);
  const [bcc, setBcc] = useState<boolean | null>(null);
  const d = defaults.data;
  const send = useMutation({
    mutationFn: () =>
      unwrap(
        api.POST("/api/v1/invoices/{id}/send", {
          params: { path: { id: invoice.id } },
          body: { to: (to ?? d?.to.join(", ") ?? "").split(/[,;\s]+/).filter(Boolean), subject: subject ?? d?.subject, message: message ?? d?.message, attach_pdf: attach, bcc_me: bcc ?? d?.bcc_me ?? false },
        }),
      ),
    onSuccess: (inv) => {
      onSent(inv);
      onClose();
      toast(t("invoices.sentToast", { to: inv.sent_to.join(", ") }));
    },
  });
  const err = send.error ? errorInfo(send.error) : null;
  // Without email set up the send would only fail at the end, so say it before anything is typed.
  const noEmail = useAuthConfig()?.email_configured === false;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={invoice.number ? t("invoices.sendTitleNumber", { number: invoice.number }) : t("invoices.sendTitle")} description={invoice.state === "draft" ? t("invoices.sendLead") : undefined} wide>
      {!d ? (
        <p className="muted">{t("app.loading")}</p>
      ) : (
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            send.mutate();
          }}
        >
          {noEmail && (
            <p className="notice notice-warn">
              {t("invoices.sendForm.noEmail")}{" "}
              <a href="https://github.com/honestrobin/time/blob/main/docs/self-host.md#configuration" target="_blank" rel="noreferrer">
                {t("invoices.sendForm.noEmailHow")}
              </a>
            </p>
          )}
          <TextField label={t("invoices.sendForm.to")} hint={t("invoices.sendForm.toHint")} value={to ?? d.to.join(", ")} onChange={setTo} error={err?.fields.to} autoFocus />
          <TextField label={t("invoices.sendForm.subject")} value={subject ?? d.subject} onChange={setSubject} />
          <TextAreaField label={t("invoices.sendForm.message")} value={message ?? d.message} onChange={setMessage} rows={7} />
          <Checkbox checked={attach} onChange={setAttach} label={t("invoices.sendForm.attach")} />
          <Checkbox checked={bcc ?? d.bcc_me} onChange={setBcc} label={t("invoices.sendForm.bcc")} />
          {err && !Object.keys(err.fields).length && <p className="notice notice-error">{err.message}</p>}
          <DialogActions>
            <Button onClick={onClose}>{t("app.cancel")}</Button>
            <Button type="submit" variant="primary" busy={send.isPending} disabled={noEmail}>
              {t("invoices.sendForm.submit")}
            </Button>
          </DialogActions>
        </form>
      )}
    </Dialog>
  );
}

function PaymentDialog({ invoice, onClose, onSaved }: { invoice: Invoice; onClose: () => void; onSaved: (inv: Invoice) => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const today = new Date();
  const [amount, setAmount] = useState<number | null>(invoice.due);
  const [date, setDate] = useState(`${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`);
  const [notes, setNotes] = useState("");
  const pay = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/invoices/{id}/payments", { params: { path: { id: invoice.id } }, body: { amount: amount ?? 0, paid_date: date, notes: notes || undefined } as never })),
    onSuccess: (inv) => {
      onSaved(inv);
      onClose();
      toast(inv.state === "paid" ? t("invoices.paidToast") : t("invoices.paymentToast"));
    },
  });
  const err = pay.error ? errorInfo(pay.error) : null;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t("invoices.recordPayment")} description={t("invoices.paymentLead", { due: formatMoney(invoice.due, invoice.currency) })}>
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          pay.mutate();
        }}
      >
        <div className="form-grid">
          <MoneyField label={t("invoices.col.amount")} value={amount} onChange={setAmount} currency={invoice.currency} error={err?.fields.amount} />
          <TextField type="date" label={t("invoices.col.date")} value={date} onChange={setDate} />
        </div>
        <TextField label={t("invoices.col.notes")} hint={t("invoices.paymentNotesHint")} value={notes} onChange={setNotes} />
        {err && !Object.keys(err.fields).length && <p className="notice notice-error">{err.message}</p>}
        <DialogActions>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button type="submit" variant="primary" busy={pay.isPending}>
            {t("invoices.recordPayment")}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
