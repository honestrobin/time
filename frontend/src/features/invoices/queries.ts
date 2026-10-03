// SPDX-License-Identifier: AGPL-3.0-only
import { queryOptions } from "@tanstack/react-query";
import { api, unwrap, type Schemas } from "../../lib/api";

export type Invoice = Schemas["InvoiceView"];
export type InvoiceSummaryRow = Schemas["InvoiceSummaryView"];
export type InvoiceLine = Schemas["InvoiceLineView"];

export const invoiceKeys = {
  all: ["invoices"] as const,
  list: (state: string) => ["invoices", "list", state] as const,
  summary: ["invoices", "summary"] as const,
  one: (id: string) => ["invoices", id] as const,
};

export const invoiceQuery = (id: string) =>
  queryOptions({ queryKey: invoiceKeys.one(id), queryFn: () => unwrap(api.GET("/api/v1/invoices/{id}", { params: { path: { id } } })) });

export const invoiceSettingsQuery = queryOptions({ queryKey: ["invoice_settings"], queryFn: () => unwrap(api.GET("/api/v1/invoice_settings")) });

/** The state a person sees: overdue is a sent invoice past its due date. */
export function displayState(i: { state: string; is_overdue: boolean }): string {
  if (i.is_overdue) return "overdue";
  return i.state === "open" ? "sent" : i.state;
}

export function stateBadge(state: string): string {
  switch (state) {
    case "paid":
      return "badge badge-ok";
    case "overdue":
      return "badge badge-signal";
    case "partially_paid":
      return "badge badge-warn";
    default:
      return "badge";
  }
}
