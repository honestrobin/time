// SPDX-License-Identifier: AGPL-3.0-only
// The second step of signing in when two-factor sign-in is on: a code from the app, or a recovery code.
import { useMutation } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, TextField } from "../../design";
import { api, errorInfo, unwrap, type Schemas } from "../../lib/api";

export type SignedInResponse = Schemas["SignedInResponse"];

export function SecondFactorForm({ challenge, onSignedIn }: { challenge: string; onSignedIn: (res: SignedInResponse) => void | Promise<void> }) {
  const { t } = useTranslation();
  const [recovery, setRecovery] = useState(false);
  const [value, setValue] = useState("");
  const verify = useMutation({
    mutationFn: () =>
      unwrap(api.POST("/api/v1/auth/two_factor", { body: recovery ? { challenge, recovery_code: value } : { challenge, code: value } })),
    onSuccess: onSignedIn,
  });
  const error = verify.error ? errorInfo(verify.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    verify.mutate();
  };

  return (
    <form className="stack" onSubmit={submit} noValidate>
      <p className="lead">{recovery ? t("auth.twoFactorRecoveryLead") : t("auth.twoFactorLead")}</p>
      {error && (
        <p className="notice notice-error" role="alert">
          {error.message}
        </p>
      )}
      {recovery ? (
        <TextField key="recovery" label={t("auth.recoveryCode")} autoComplete="off" autoFocus value={value} onChange={setValue} placeholder="xxxxx-xxxxx" />
      ) : (
        <TextField
          key="code"
          label={t("auth.twoFactorCode")}
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={7}
          autoFocus
          value={value}
          onChange={setValue}
          placeholder="123456"
        />
      )}
      <Button type="submit" variant="primary" block busy={verify.isPending} disabled={!value.trim()}>
        {t("auth.twoFactorVerify")}
      </Button>
      <button
        type="button"
        className="link-button"
        onClick={() => {
          setRecovery(!recovery);
          setValue("");
          verify.reset();
        }}
      >
        {recovery ? t("auth.useAppCode") : t("auth.useRecoveryCode")}
      </button>
    </form>
  );
}
