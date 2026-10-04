// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, TextField } from "../../design";
import { ApiError, api, errorInfo, unwrap } from "../../lib/api";
import { AuthLayout } from "./AuthLayout";
import { SecondFactorForm } from "./SecondFactor";

/** Tokens arrive in the URL fragment so they never reach server logs or Referer headers. */
function tokenFromHash() {
  return location.hash.replace(/^#/, "");
}

export function ForgotPasswordPage() {
  const { t } = useTranslation();
  const [email, setEmail] = useState("");
  const [sentTo, setSentTo] = useState<string | null>(null);
  const request = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/auth/password_reset", { body: { email } })),
    onSuccess: () => setSentTo(email),
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    request.mutate();
  };
  return (
    <AuthLayout>
      <h1>{t("auth.forgotTitle")}</h1>
      <p className="lead">{t("auth.forgotLead")}</p>
      {sentTo ? (
        <p className="notice notice-ok" role="status">
          {t("auth.resetSent", { email: sentTo })}
        </p>
      ) : (
        <form className="stack" onSubmit={submit}>
          <TextField label={t("auth.email")} type="email" autoComplete="email" value={email} onChange={setEmail} autoFocus />
          <Button type="submit" variant="primary" block busy={request.isPending}>
            {t("auth.sendResetLink")}
          </Button>
        </form>
      )}
      <div className="auth-links">
        <Link to="/login">{t("auth.backToSignIn")}</Link>
      </div>
    </AuthLayout>
  );
}

/**
 * Signs in only when the person presses the button, not when the page loads: a link a mail
 * scanner opens isn't used up, and a page that sends someone here can't sign them in to an
 * account that isn't theirs without them noticing.
 */
export function MagicLinkPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [token] = useState(tokenFromHash);
  const [challenge, setChallenge] = useState<string | null>(null);
  const finish = async () => {
    await qc.invalidateQueries();
    await navigate({ to: "/" });
  };
  const consume = useMutation({
    mutationFn: (token: string) => unwrap(api.POST("/api/v1/auth/magic_link/consume", { body: { token } })),
    onSuccess: (res) => {
      history.replaceState(null, "", location.pathname);
      return res.two_factor_required && res.challenge ? setChallenge(res.challenge) : finish();
    },
  });
  if (challenge) {
    return (
      <AuthLayout>
        <h1>{t("auth.twoFactorTitle")}</h1>
        <SecondFactorForm challenge={challenge} onSignedIn={finish} />
      </AuthLayout>
    );
  }
  return (
    <AuthLayout>
      <h1>{t("auth.magicTitle")}</h1>
      {!consume.isError && (
        <>
          <p className="lead">{t("auth.magicLead")}</p>
          <Button variant="primary" block busy={consume.isPending} onClick={() => consume.mutate(token)}>
            {t("auth.magicContinue")}
          </Button>
        </>
      )}
      {consume.isError && (
        <>
          <p className="notice notice-error" role="alert">
            {t("auth.linkInvalid")}
          </p>
          <div className="auth-links">
            <Link to="/login">{t("auth.requestNewLink")}</Link>
          </div>
        </>
      )}
    </AuthLayout>
  );
}

export function ResetPasswordPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [token] = useState(tokenFromHash);
  const [password, setPassword] = useState("");
  const [challenge, setChallenge] = useState<string | null>(null);
  const finish = async () => {
    await qc.invalidateQueries();
    await navigate({ to: "/" });
  };
  const reset = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/auth/password_reset/consume", { body: { token, password } })),
    onSuccess: (res) => {
      history.replaceState(null, "", location.pathname);
      return res.two_factor_required && res.challenge ? setChallenge(res.challenge) : finish();
    },
  });
  const err = reset.error ? errorInfo(reset.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    reset.mutate();
  };
  if (challenge) {
    return (
      <AuthLayout>
        <h1>{t("auth.twoFactorTitle")}</h1>
        <p className="notice notice-ok">{t("auth.passwordChangedNeedCode")}</p>
        <SecondFactorForm challenge={challenge} onSignedIn={finish} />
      </AuthLayout>
    );
  }
  return (
    <AuthLayout>
      <h1>{t("auth.resetTitle")}</h1>
      <form className="stack" onSubmit={submit} style={{ marginTop: 24 }}>
        {err && err.code === "invalid_link" && (
          <p className="notice notice-error" role="alert">
            {t("auth.linkInvalid")} <Link to="/forgot">{t("auth.requestNewLink")}</Link>
          </p>
        )}
        <TextField
          label={t("auth.newPassword")}
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={setPassword}
          hint={t("auth.passwordHint")}
          error={err?.fields.password}
          autoFocus
        />
        <Button type="submit" variant="primary" block busy={reset.isPending}>
          {t("auth.setPassword")}
        </Button>
      </form>
    </AuthLayout>
  );
}

/** The link from the confirmation email: confirms the address, signed in or not. */
export function VerifyEmailPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const started = useRef(false);
  const verify = useMutation({
    mutationFn: (token: string) => unwrap(api.POST("/api/v1/auth/verify_email", { body: { token } })),
    onSuccess: async () => {
      history.replaceState(null, "", location.pathname);
      await qc.invalidateQueries({ queryKey: ["me"] });
    },
  });
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    verify.mutate(tokenFromHash());
  }, [verify]);

  return (
    <AuthLayout>
      <h1>{t("auth.verifyTitle")}</h1>
      {verify.isSuccess && (
        <>
          <p className="notice notice-ok" role="status">
            {t("auth.verifyDone")}
          </p>
          <div className="auth-links">
            <Link to="/">{t("auth.verifyContinue")}</Link>
          </div>
        </>
      )}
      {verify.isError && verify.error instanceof ApiError && verify.error.status === 401 ? (
        <>
          <p className="notice" role="status">
            {t("auth.verifySignIn")}
          </p>
          <div className="auth-links">
            {/* Not back here with the token: in the query it would reach logs. */}
            <Link to="/login">
              {t("auth.verifySignInLink")}
            </Link>
          </div>
        </>
      ) : (
        verify.isError && (
          <p className="notice notice-error" role="alert">
            {verify.error instanceof ApiError && verify.error.status === 403 ? t("auth.verifyWrongUser") : t("auth.verifyInvalid")}
          </p>
        )
      )}
    </AuthLayout>
  );
}
