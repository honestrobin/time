// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Dialog, DialogActions, SelectField, TextField, useToast } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { CURRENCIES } from "../../lib/reference";
import { clientKeys, useAccountDefaults, type Client } from "./queries";

/** Creates a client with just a name and currency, without leaving the project form. */
export function QuickClientDialog({ open, onOpenChange, onCreated }: { open: boolean; onOpenChange: (o: boolean) => void; onCreated: (c: Client) => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const defaults = useAccountDefaults();
  const [name, setName] = useState("");
  const [currency, setCurrency] = useState(defaults.currency);

  const create = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/clients", { body: { name: name.trim(), currency } })),
    onSuccess: (c) => {
      // Show the new client in selects right away, then refresh from the server.
      qc.setQueryData(clientKeys.list, (old: { data: Client[]; next_cursor?: string } | undefined) => (old ? { ...old, data: [...old.data, c] } : old));
      void qc.invalidateQueries({ queryKey: clientKeys.all });
      toast(t("clients.created"));
      onCreated(c);
      onOpenChange(false);
    },
  });

  useEffect(() => {
    if (open) {
      setName("");
      setCurrency(defaults.currency);
      create.reset();
    }
    // Reset only when the dialog opens.
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  const err = create.error ? errorInfo(create.error) : null;
  const currencies = CURRENCIES.includes(currency) ? CURRENCIES : [currency, ...CURRENCIES];
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={t("clients.newClient")} description={t("projects.form.quickClientLead")}>
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          e.stopPropagation();
          create.mutate();
        }}
      >
        <TextField label={t("clients.fields.name")} value={name} onChange={setName} error={err?.fields.name} autoFocus required />
        <SelectField
          label={t("clients.fields.currency")}
          hint={t("clients.fields.currencyHint")}
          value={currency}
          onChange={setCurrency}
          options={currencies.map((c) => ({ value: c, label: c }))}
          error={err?.fields.currency}
        />
        {err && !Object.keys(err.fields).length && <p className="notice notice-error">{err.message}</p>}
        <DialogActions>
          <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
          <Button type="submit" variant="primary" busy={create.isPending}>
            {t("clients.create")}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
