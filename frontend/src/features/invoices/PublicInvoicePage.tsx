// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import "./invoices.css";

type PublicInvoiceData = Schemas["PublicInvoiceView"];
type Party = PublicInvoiceData["document"]["seller"];

/** The invoice as the client sees it from the email link: no account needed, a PDF to keep, and online payment. */
export function PublicInvoicePage() {
  const { t } = useTranslation();
  const { token } = useParams({ strict: false }) as { token: string };
  const q = useQuery({
    queryKey: ["public-invoice", token],
    queryFn: () => unwrap(api.GET("/api/v1/public/invoices/{token}", { params: { path: { token } } })),
    retry: false,
  });
  if (q.error) {
    return (
      <main className="public-invoice">
        <p className="notice notice-error">{errorInfo(q.error).code === "not_found" ? t("publicInvoice.notFound") : errorInfo(q.error).message}</p>
      </main>
    );
  }
  if (!q.data) return <main className="public-invoice">{t("app.loading")}</main>;
  return <PublicInvoice token={token} data={q.data} />;
}

function PartyBlock({ party }: { party: Party }) {
  const { t } = useTranslation();
  return (
    <>
      <strong>{party.name}</strong>
      {party.lines.map((l) => (
        <div key={l}>{l}</div>
      ))}
      {party.tax_id && <div className="muted">{t("publicInvoice.taxId", { id: party.tax_id })}</div>}
    </>
  );
}

function PublicInvoice({ token, data }: { token: string; data: PublicInvoiceData }) {
  const { t } = useTranslation();
  const d = data.document;
  // Stripe sends the payer back with ?paid=1; the webhook marks the invoice paid moments later.
  const returned = new URLSearchParams(location.search).get("paid") === "1";
  const pay = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/public/invoices/{token}/checkout", { params: { path: { token } } })),
    onSuccess: (r) => {
      window.location.href = r.url;
    },
  });
  return (
    <main className="public-invoice">
      <div className="public-invoice-bar">
        <span className="muted">{t("publicInvoice.from", { seller: d.seller.name })}</span>
        <div className="row">
          <a className="btn" href={data.pdf_url}>
            {t("publicInvoice.download")}
          </a>
          {data.can_pay_online && !returned && (
            <button type="button" className="btn btn-primary" onClick={() => pay.mutate()} disabled={pay.isPending}>
              {t("publicInvoice.payNow", { amount: d.amount_due })}
            </button>
          )}
        </div>
      </div>
      {returned && data.state !== "paid" && <p className="notice notice-ok">{t("publicInvoice.thanks")}</p>}
      {pay.error && <p className="notice notice-error">{errorInfo(pay.error).message}</p>}
      <article className="public-invoice-paper">
        <header className="public-invoice-head">
          <div>
            <h1>
              {d.title} {d.number}
            </h1>
            <PartyBlock party={d.seller} />
          </div>
          <dl>
            <dt>{t("publicInvoice.issued")}</dt>
            <dd>{d.issue_date}</dd>
            {d.due_date && (
              <>
                <dt>{t("publicInvoice.due")}</dt>
                <dd>{d.due_date}</dd>
              </>
            )}
            {d.purchase_order && (
              <>
                <dt>{t("publicInvoice.po")}</dt>
                <dd>{d.purchase_order}</dd>
              </>
            )}
          </dl>
        </header>
        <section className="public-invoice-billto">
          <h2>{t("publicInvoice.billTo")}</h2>
          <PartyBlock party={d.buyer} />
        </section>
        {d.subject && <p className="public-invoice-subject">{d.subject}</p>}
        <div className="table-scroll">
          <table className="ledger">
            <thead>
              <tr>
                <th>{t("invoices.col.description")}</th>
                <th className="num">{t("invoices.col.quantity")}</th>
                <th className="num">{t("invoices.col.unitPrice")}</th>
                {d.show_vat_column && <th className="num">{t("invoices.col.vat")}</th>}
                <th className="num">{t("invoices.col.amount")}</th>
              </tr>
            </thead>
            <tbody>
              {d.lines.map((l, i) => (
                <tr key={i}>
                  <td className="pre">{l.description}</td>
                  <td className="num">{l.quantity}</td>
                  <td className="num">{l.unit_price}</td>
                  {d.show_vat_column && <td className="num">{l.vat}</td>}
                  <td className="num">{l.amount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <dl className="invoice-totals">
          {d.rows.map((r) => (
            <div key={r.label} className={r.strong ? "grand" : undefined}>
              <dt>{r.label}</dt>
              <dd>{r.amount}</dd>
            </div>
          ))}
        </dl>
        {data.state !== "void" && (
          <div className={`public-invoice-due${data.state === "paid" ? " paid" : ""}`}>
            <span>{data.state === "paid" ? t("publicInvoice.paid") : t("publicInvoice.amountDue")}</span>
            <strong>{d.amount_due}</strong>
          </div>
        )}
        {d.legal_note && <p className="public-invoice-legal">{d.legal_note}</p>}
        {d.payment_instructions && (
          <section>
            <h2>{t("publicInvoice.howToPay")}</h2>
            <p className="pre">{d.payment_instructions}</p>
          </section>
        )}
        {d.notes && (
          <section>
            <h2>{t("publicInvoice.notes")}</h2>
            <p className="pre">{d.notes}</p>
          </section>
        )}
        {d.footer && <footer className="muted small">{d.footer}</footer>}
      </article>
    </main>
  );
}
