// SPDX-License-Identifier: AGPL-3.0-only
// Shown instead of every page when the account requires two-factor sign-in and this person hasn't set it up.
import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { PageHeader } from "../../design";
import { useMe } from "../../lib/session";
import { RecoveryCodesView, TwoFactorSetup } from "./TwoFactorSection";

export function TwoFactorGate() {
  const { t } = useTranslation();
  const me = useMe();
  const qc = useQueryClient();
  const [codes, setCodes] = useState<string[] | null>(null);
  const account = me.accounts.find((a) => a.id === me.current_account_id)?.name ?? "";
  return (
    <div className="page page-narrow">
      <PageHeader title={t("settings.twoFactorGateTitle")} lead={t("settings.twoFactorGateLead", { account })} />
      {codes ? <RecoveryCodesView codes={codes} onDone={() => void qc.invalidateQueries()} /> : <TwoFactorSetup onEnabled={setCodes} />}
    </div>
  );
}
