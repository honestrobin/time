// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, TextField } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { useAuthConfig } from "../../lib/session";
import { AuthLayout } from "./AuthLayout";
import { SecondFactorForm } from "./SecondFactor";

export function LoginPage() {
  const { t } = useTranslation();
  const config = useAuthConfig();
  const navigate = useNavigate();
  const search = useSearch({ strict: false }) as { next?: string };
  const qc = useQueryClient();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [linkSentTo, setLinkSentTo] = useState<string | null>(null);
  const [challenge, setChallenge] = useState<string | null>(null);

  const finish = async () => {
    await qc.invalidateQueries();
    const next = search.next && search.next.startsWith("/") && !search.next.startsWith("//") ? search.next : "/";
    await navigate({ to: next });
  };
  const login = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/auth/login", { body: { email, password } })),
    onSuccess: (res) => (res.two_factor_required && res.challenge ? setChallenge(res.challenge) : finish()),
  });
  const magic = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/auth/magic_link", { body: { email } })),
    onSuccess: () => setLinkSentTo(email),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    login.mutate();
  };
  const error = login.error ? errorInfo(login.error) : null;

  if (challenge) {
    return (
      <AuthLayout>
        <h1>{t("auth.twoFactorTitle")}</h1>
        <SecondFactorForm challenge={challenge} onSignedIn={finish} />
      </AuthLayout>
    );
  }
  return (
    <AuthLayout robin="promise">
      <h1>{t("auth.signInTitle")}</h1>
      <p className="lead">{t("auth.signInLead")}</p>
      {linkSentTo ? (
        <p className="notice notice-ok" role="status">
          {t(config?.email_configured === false ? "auth.emailLinkInLog" : "auth.emailLinkSent", { email: linkSentTo })}
        </p>
      ) : (
        <form className="stack" onSubmit={submit} noValidate>
          {error && (
            <p className="notice notice-error" role="alert">
              {error.message}
            </p>
          )}
          <TextField label={t("auth.email")} type="email" autoComplete="email" value={email} onChange={setEmail} required autoFocus />
          <TextField label={t("auth.password")} type="password" autoComplete="current-password" value={password} onChange={setPassword} />
          <Button type="submit" variant="primary" block busy={login.isPending}>
            {t("auth.signIn")}
          </Button>
          <Button variant="secondary" block disabled={!email.includes("@")} busy={magic.isPending} onClick={() => magic.mutate()}>
            {t("auth.emailLink")}
          </Button>
        </form>
      )}
      <div className="auth-links">
        <Link to="/forgot">{t("auth.forgot")}</Link>
        {config?.signup_allowed && (
          <span>
            {t("auth.noAccount")} <Link to="/signup">{t("auth.createAccount")}</Link>
          </span>
        )}
        {config && !config.signup_allowed && <span>{t("auth.inviteOnly")}</span>}
      </div>
    </AuthLayout>
  );
}
