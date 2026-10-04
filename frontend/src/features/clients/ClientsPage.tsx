// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, ConfirmDialog, EmptyState, LoadingRow, Menu, PageHeader, useToast } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { usePermissions } from "../../lib/session";
import { byName, clientKeys, clientsQuery, type Client } from "../projects/queries";
import { ClientDialog } from "./ClientDialog";
import "../projects/catalog.css";

/** The account's clients: who you bill, in which currency, and who receives the invoices. */
export function ClientsPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const perms = usePermissions();
  const q = useQuery(clientsQuery);
  const clients = [...(q.data?.data ?? [])].sort((a, b) => Number(b.is_active) - Number(a.is_active) || byName(a, b));

  // null: closed; "new": creating; otherwise the id of the client being edited.
  const [open, setOpen] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<Client | null>(null);

  const setActive = useMutation({
    mutationFn: (c: Client) => unwrap(api.PATCH("/api/v1/clients/{id}", { params: { path: { id: c.id } }, body: { is_active: !c.is_active } })),
    onSuccess: (c) => {
      void qc.invalidateQueries({ queryKey: clientKeys.all });
      void qc.invalidateQueries({ queryKey: ["projects"] });
      toast(c.is_active ? t("clients.restored", { name: c.name }) : t("clients.archived", { name: c.name }));
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  const remove = useMutation({
    mutationFn: (c: Client) => unwrap(api.DELETE("/api/v1/clients/{id}", { params: { path: { id: c.id } } })),
    onSuccess: (_, c) => {
      setDeleting(null);
      void qc.invalidateQueries({ queryKey: clientKeys.all });
      toast(t("clients.deleted", { name: c.name }));
    },
  });

  const cols = 7;
  const canEdit = perms.canManageProjects;

  return (
    <div className="page">
      <PageHeader
        title={t("clients.title")}
        lead={t("clients.lead")}
        actions={
          canEdit && (
            <Button variant="primary" onClick={() => setOpen("new")}>
              {t("clients.newClient")}
            </Button>
          )
        }
      />

      {q.isError ? (
        <p className="notice notice-error" role="alert">
          {errorInfo(q.error).message}
        </p>
      ) : !q.isLoading && clients.length === 0 ? (
        <EmptyState
          robin="notes"
          title={t("clients.empty.title")}
          body={t("clients.empty.body")}
          action={
            canEdit && (
              <Button variant="primary" onClick={() => setOpen("new")}>
                {t("clients.empty.action")}
              </Button>
            )
          }
        />
      ) : (
        <table className="ledger">
          <thead>
            <tr>
              <th>{t("clients.fields.name")}</th>
              <th className="col-narrow">{t("clients.fields.currency")}</th>
              <th>{t("clients.fields.vatId")}</th>
              <th className="col-narrow">{t("clients.list.country")}</th>
              <th className="num col-narrow">{t("clients.list.contacts")}</th>
              <th className="col-narrow">{t("clients.list.status")}</th>
              <th>
                <span className="sr-only">{t("clients.list.actions")}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {q.isLoading && <LoadingRow colSpan={cols} />}
            {clients.map((c) => (
              <tr key={c.id} className={["is-clickable", c.is_active ? "" : "is-archived"].join(" ")} onClick={() => canEdit && setOpen(c.id)}>
                <td>
                  {canEdit ? (
                    <button
                      type="button"
                      className="link-button"
                      onClick={(e) => {
                        e.stopPropagation();
                        setOpen(c.id);
                      }}
                    >
                      {c.name}
                    </button>
                  ) : (
                    <strong>{c.name}</strong>
                  )}
                </td>
                <td>{c.currency}</td>
                <td className="code">{c.vat_id ?? ""}</td>
                <td>{c.country_code ?? ""}</td>
                <td className="num">{c.contacts.length || ""}</td>
                <td>{c.is_active ? t("clients.list.active") : <span className="badge">{t("clients.list.archivedBadge")}</span>}</td>
                <td className="actions" onClick={(e) => e.stopPropagation()}>
                  {canEdit && (
                    <Menu
                      trigger={
                        <Button size="sm" variant="ghost" aria-label={t("clients.list.actionsFor", { name: c.name })}>
                          ⋯
                        </Button>
                      }
                      items={[
                        { label: t("clients.edit"), onSelect: () => setOpen(c.id) },
                        { label: c.is_active ? t("clients.archive") : t("clients.restore"), onSelect: () => setActive.mutate(c) },
                        "separator",
                        {
                          label: t("clients.delete"),
                          danger: true,
                          onSelect: () => {
                            remove.reset();
                            setDeleting(c);
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

      <ClientDialog clientId={open} onClose={() => setOpen(null)} onCreated={(id) => setOpen(id)} />

      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(o) => !o && setDeleting(null)}
        title={t("clients.deleteTitle", { name: deleting?.name ?? "" })}
        body={
          <div className="stack">
            <p>{t("clients.deleteBody")}</p>
            {remove.error && (
              <p className="notice notice-error" role="alert">
                {errorInfo(remove.error).message}
              </p>
            )}
          </div>
        }
        confirmLabel={t("clients.delete")}
        busy={remove.isPending}
        onConfirm={() => deleting && remove.mutate(deleting)}
      />
    </div>
  );
}
