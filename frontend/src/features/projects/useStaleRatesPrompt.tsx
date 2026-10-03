// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Dialog, DialogActions, useToast } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { usePermissions } from "../../lib/session";

/**
 * Rates are snapshotted on entries (spec §5.1). After a change that affects rates, ask whether to
 * re-rate the project's uninvoiced, unlocked entries. Returns the dialog element and `check()`.
 */
export function useStaleRatesPrompt(projectId: string) {
  const { t } = useTranslation();
  const perms = usePermissions();
  const toast = useToast();
  const qc = useQueryClient();
  const [count, setCount] = useState<number | null>(null);

  const apply = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/rates/apply", { body: { project_id: projectId } })),
    onSuccess: (res) => {
      setCount(null);
      toast(t("projects.rates.updated", { count: res.entries }));
      // Entry amounts changed everywhere: timesheets, budgets, reports.
      void qc.invalidateQueries();
    },
  });

  const canManageRates = perms.canSeeRates && perms.isManagerOrAdmin;
  const check = async () => {
    if (!canManageRates) return;
    try {
      const res = await unwrap(api.GET("/api/v1/rates/stale", { params: { query: { project_id: projectId } } }));
      if (res.entries > 0) {
        apply.reset();
        setCount(res.entries);
      }
    } catch {
      // The change itself was saved; failing to count stale entries is not worth interrupting for.
    }
  };

  const n = count ?? 0;
  const element = (
    <Dialog open={count !== null} onOpenChange={(o) => !o && setCount(null)} title={t("projects.rates.title", { count: n })}>
      <div className="stack">
        <p>{t("projects.rates.body", { count: n })}</p>
        <p className="muted">{t("projects.rates.kept")}</p>
        {apply.error && <p className="notice notice-error">{errorInfo(apply.error).message}</p>}
      </div>
      <DialogActions>
        <Button onClick={() => setCount(null)}>{t("projects.rates.keep")}</Button>
        <Button variant="primary" busy={apply.isPending} onClick={() => apply.mutate()}>
          {t("projects.rates.apply", { count: n })}
        </Button>
      </DialogActions>
    </Dialog>
  );
  return { element, check };
}
