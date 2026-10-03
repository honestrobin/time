// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Checkbox, Dialog, DialogActions, EmptyState, LoadingRow, MoneyField, PageHeader, TextField, useToast } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";
import { formatMoney } from "../../lib/format";
import { usePermissions } from "../../lib/session";
import { useAccountSettings } from "../time/hooks";

type Category = Schemas["ExpenseCategoryView"];

const categoriesQuery = { queryKey: ["expense_categories"], queryFn: () => unwrap(api.GET("/api/v1/expense_categories")) };

export function ExpenseCategoriesPage() {
  const { t } = useTranslation();
  const perms = usePermissions();
  const account = useAccountSettings();
  const qc = useQueryClient();
  const toast = useToast();
  const q = useQuery(categoriesQuery);
  const [dialog, setDialog] = useState<{ open: boolean; category: Category | null }>({ open: false, category: null });

  const archive = useMutation({
    mutationFn: (c: Category) =>
      unwrap(api.PATCH("/api/v1/expense_categories/{id}", { params: { path: { id: c.id } }, body: { is_active: !c.is_active } })),
    onSuccess: (c) => {
      void qc.invalidateQueries({ queryKey: ["expense_categories"] });
      toast(c.is_active ? t("categories.unarchived", { name: c.name }) : t("categories.archived", { name: c.name }));
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  // Active first, then archived; each alphabetical (the API sorts by name).
  const list = [...(q.data ?? [])].sort((a, b) => Number(b.is_active) - Number(a.is_active));
  const unit = (c: Category) => {
    if (!c.is_unit_priced) return <span className="muted">{t("categories.freeAmount")}</span>;
    return c.unit_price !== undefined
      ? t("categories.unitAt", { unit: c.unit_name, price: formatMoney(c.unit_price, account.currency) })
      : t("categories.perUnit", { unit: c.unit_name });
  };
  const cols = perms.isAdmin ? 4 : 3;

  return (
    <div className="page">
      <PageHeader
        title={t("categories.title")}
        lead={t("categories.lead")}
        actions={
          perms.isAdmin && (
            <Button variant="primary" onClick={() => setDialog({ open: true, category: null })}>
              {t("categories.new")}
            </Button>
          )
        }
      />
      {!perms.isAdmin && <p className="notice" style={{ marginBottom: 16 }}>{t("categories.adminOnly")}</p>}
      {q.isError ? (
        <p className="notice notice-error" role="alert">
          {errorInfo(q.error).message}
        </p>
      ) : !q.isLoading && list.length === 0 ? (
        <EmptyState
          title={t("categories.emptyTitle")}
          body={t("categories.emptyBody")}
          action={
            perms.isAdmin && (
              <Button variant="primary" onClick={() => setDialog({ open: true, category: null })}>
                {t("categories.new")}
              </Button>
            )
          }
        />
      ) : (
        <table className="ledger">
          <thead>
            <tr>
              <th>{t("categories.name")}</th>
              <th>{t("categories.unit")}</th>
              <th>{t("categories.status")}</th>
              {perms.isAdmin && (
                <th>
                  <span className="sr-only">{t("categories.actions")}</span>
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {q.isLoading && <LoadingRow colSpan={cols} />}
            {list.map((c) => (
              <tr key={c.id}>
                <td className={c.is_active ? undefined : "muted"}>{c.name}</td>
                <td>{unit(c)}</td>
                <td>{c.is_active ? t("categories.active") : <span className="badge">{t("categories.archivedBadge")}</span>}</td>
                {perms.isAdmin && (
                  <td className="num" style={{ width: 1, whiteSpace: "nowrap" }}>
                    <div className="row" style={{ justifyContent: "flex-end" }}>
                      <Button size="sm" variant="ghost" onClick={() => setDialog({ open: true, category: c })} aria-label={t("categories.editNamed", { name: c.name })}>
                        {t("app.edit")}
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        busy={archive.isPending && archive.variables?.id === c.id}
                        onClick={() => archive.mutate(c)}
                        aria-label={c.is_active ? t("categories.archiveNamed", { name: c.name }) : t("categories.unarchiveNamed", { name: c.name })}
                      >
                        {c.is_active ? t("categories.archive") : t("categories.unarchive")}
                      </Button>
                    </div>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <CategoryDialog
        open={dialog.open}
        category={dialog.category}
        currency={account.currency}
        onOpenChange={(open) => setDialog((d) => ({ ...d, open }))}
      />
    </div>
  );
}

function CategoryDialog({
  open,
  category,
  currency,
  onOpenChange,
}: {
  open: boolean;
  category: Category | null;
  currency: string;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const toast = useToast();
  const [name, setName] = useState("");
  const [perUnit, setPerUnit] = useState(false);
  const [unitName, setUnitName] = useState("");
  const [unitPrice, setUnitPrice] = useState<number | null>(null);

  const save = useMutation({
    mutationFn: () => {
      const body = perUnit ? { name, unit_name: unitName, unit_price: unitPrice } : { name, unit_name: null, unit_price: null };
      return category
        ? unwrap(api.PATCH("/api/v1/expense_categories/{id}", { params: { path: { id: category.id } }, body: body as never }))
        : unwrap(api.POST("/api/v1/expense_categories", { body: (perUnit ? body : { name }) as never }));
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["expense_categories"] });
      toast(category ? t("app.saved") : t("categories.created"));
      onOpenChange(false);
    },
  });

  useEffect(() => {
    if (!open) return;
    setName(category?.name ?? "");
    setPerUnit(category?.is_unit_priced ?? false);
    setUnitName(category?.unit_name ?? "");
    setUnitPrice(category?.unit_price ?? null);
    save.reset();
  }, [open, category]);

  const err = save.error ? errorInfo(save.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    save.mutate();
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={category ? t("categories.editTitle") : t("categories.newTitle")}>
      <form className="stack" onSubmit={submit}>
        {err && !Object.keys(err.fields).length && (
          <p className="notice notice-error" role="alert">
            {err.message}
          </p>
        )}
        <TextField label={t("categories.name")} hint={t("categories.nameHint")} value={name} onChange={setName} error={err?.fields.name} autoFocus required />
        <Checkbox checked={perUnit} onChange={setPerUnit} label={t("categories.perUnitToggle")} hint={t("categories.perUnitHint")} />
        {perUnit && (
          <div className="form-grid">
            <TextField
              label={t("categories.unitName")}
              hint={t("categories.unitNameHint")}
              value={unitName}
              onChange={setUnitName}
              error={err?.fields.unit_name}
              required
            />
            <MoneyField
              label={t("categories.unitPrice")}
              hint={t("categories.unitPriceHint", { unit: unitName || t("categories.unitFallback") })}
              value={unitPrice}
              onChange={setUnitPrice}
              currency={currency}
              error={err?.fields.unit_price}
            />
          </div>
        )}
        {category && perUnit !== category.is_unit_priced && <p className="notice notice-warn">{t("categories.changeKindNote")}</p>}
        <DialogActions>
          <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
          <Button type="submit" variant="primary" busy={save.isPending}>
            {category ? t("app.save") : t("categories.create")}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
