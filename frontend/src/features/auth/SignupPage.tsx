// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, SelectField, TextField } from "../../design";
import { api, errorInfo, setAccountId, unwrap } from "../../lib/api";
import { featureOn } from "../../lib/features";
import { useAuthConfig } from "../../lib/session";
import { CURRENCIES, TIME_ZONES, guessCurrency, guessWeekStart, invoiceLocale } from "../../lib/reference";
import { AuthLayout } from "./AuthLayout";

export function SignupPage() {
  const { t } = useTranslation();
  const config = useAuthConfig();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [form, setForm] = useState({
    name: "",
    email: "",
    password: "",
    account_name: "",
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC",
    default_currency: guessCurrency(navigator.language),
    setup_code: "",
  });
  const set = (k: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [k]: v }));

  const signup = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/auth/signup", { body: { ...form, locale: invoiceLocale(navigator.language), week_start: guessWeekStart(navigator.language) } })),
    onSuccess: async (res) => {
      if (res.account_id) setAccountId(res.account_id);
      await qc.invalidateQueries();
      // The marketing site's "Import from Harvest" button signs people up with ?import=harvest,
      // which goes to the import while that feature is switched on (lib/features).
      const toImport = new URLSearchParams(location.search).get("import") === "harvest" && featureOn(config?.features, "import");
      await navigate({ to: toImport ? "/settings/import" : "/" });
    },
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    signup.mutate();
  };
  const err = signup.error ? errorInfo(signup.error) : null;

  if (config && !config.signup_allowed) {
    return (
      <AuthLayout>
        <h1>{t("auth.signupTitle")}</h1>
        <p className="notice" role="status">
          {t("auth.signupClosed")}
        </p>
        <div className="auth-links">
          <Link to="/login">{t("auth.backToSignIn")}</Link>
        </div>
      </AuthLayout>
    );
  }

  const setup = config?.needs_setup;
  const zones = TIME_ZONES.includes(form.timezone) ? TIME_ZONES : [form.timezone, ...TIME_ZONES];
  return (
    <AuthLayout robin="promise">
      <h1>{setup ? t("auth.setupTitle") : t("auth.signupTitle")}</h1>
      {setup && <p className="lead">{t("auth.setupLead")}</p>}
      <form className="stack" onSubmit={submit} noValidate style={setup ? undefined : { marginTop: 24 }}>
        {err && !Object.keys(err.fields).length && (
          <p className="notice notice-error" role="alert">
            {err.message}
          </p>
        )}
        {setup && (
          <TextField label={t("auth.setupCode")} value={form.setup_code} onChange={set("setup_code")} hint={t("auth.setupCodeHint")} autoComplete="off" autoFocus />
        )}
        <TextField label={t("auth.name")} autoComplete="name" value={form.name} onChange={set("name")} error={err?.fields.name} autoFocus={!setup} />
        <TextField label={t("auth.email")} type="email" autoComplete="email" value={form.email} onChange={set("email")} error={err?.fields.email} />
        <TextField
          label={t("auth.password")}
          type="password"
          autoComplete="new-password"
          value={form.password}
          onChange={set("password")}
          hint={t("auth.passwordHint")}
          error={err?.fields.password}
        />
        <TextField
          label={t("auth.workspaceName")}
          value={form.account_name}
          onChange={set("account_name")}
          hint={t("auth.workspaceHint")}
          error={err?.fields.account_name}
        />
        <SelectField label={t("auth.timezone")} value={form.timezone} onChange={set("timezone")} options={zones.map((z) => ({ value: z, label: z }))} />
        <SelectField
          label={t("auth.currency")}
          value={form.default_currency}
          onChange={set("default_currency")}
          options={CURRENCIES.map((c) => ({ value: c, label: c }))}
        />
        <Button type="submit" variant="primary" block busy={signup.isPending}>
          {t("auth.createWorkspace")}
        </Button>
      </form>
      {!setup && (
        <div className="auth-links">
          <span>
            {t("auth.haveAccount")} <Link to="/login">{t("auth.signIn")}</Link>
          </span>
        </div>
      )}
    </AuthLayout>
  );
}
