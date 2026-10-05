// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, TextField } from "../../design";
import { api, errorInfo, setAccountId, unwrap } from "../../lib/api";
import { AuthLayout } from "./AuthLayout";
import { SecondFactorForm } from "./SecondFactor";

export function InvitePage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [token] = useState(() => location.hash.replace(/^#/, ""));
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [challenge, setChallenge] = useState<string | null>(null);
  const finish = async (res: { account_id?: string | null }) => {
    if (res.account_id) setAccountId(res.account_id);
    await qc.invalidateQueries();
    await navigate({ to: "/" });
  };
  const lookup = useQuery({
    queryKey: ["invite", token],
    queryFn: () => unwrap(api.POST("/api/v1/auth/invite/lookup", { body: { token } })),
    retry: false,
    enabled: token.length > 0,
  });
  const accept = useMutation({
    mutationFn: () =>
      unwrap(api.POST("/api/v1/auth/invite/accept", { body: { token, name: name || undefined, password: password || undefined } })),
    onSuccess: (res) => {
      history.replaceState(null, "", location.pathname);
      return res.two_factor_required && res.challenge ? setChallenge(res.challenge) : finish(res);
    },
  });
  const err = accept.error ? errorInfo(accept.error) : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    accept.mutate();
  };

  if (challenge) {
    return (
      <AuthLayout>
        <h1>{t("auth.twoFactorTitle")}</h1>
        <SecondFactorForm challenge={challenge} onSignedIn={finish} />
      </AuthLayout>
    );
  }
  if (!token || lookup.isError) {
    return (
      <AuthLayout>
        <h1>{t("auth.inviteTitle")}</h1>
        <p className="notice notice-error" role="alert">
          {t("auth.inviteInvalid")}
        </p>
        <div className="auth-links">
          <Link to="/login">{t("auth.backToSignIn")}</Link>
        </div>
      </AuthLayout>
    );
  }
  const invite = lookup.data;
  return (
    <AuthLayout robin="promise">
      <h1>{invite ? t("auth.joinAccount", { account: invite.account_name }) : t("auth.inviteTitle")}</h1>
      {invite && (
        <>
          <p className="lead">{invite.user_exists ? t("auth.inviteExistingUser", { email: invite.email }) : t("auth.inviteNewUser", { email: invite.email })}</p>
          <form className="stack" onSubmit={submit}>
            {err?.code === "invitation_needs_team_plan" ? (
              // The workspace's paid plan has ended and its free plan is full: nothing for them to fix here.
              <p className="notice" role="alert">
                {t("auth.inviteNeedsTeam", { account: invite.account_name })}
              </p>
            ) : (
              err &&
              !Object.keys(err.fields).length && (
                <p className="notice notice-error" role="alert">
                  {err.message}
                </p>
              )
            )}
            {!invite.user_exists && (
              <>
                <TextField label={t("auth.name")} autoComplete="name" value={name} onChange={setName} placeholder={invite.name} autoFocus />
                <TextField
                  label={t("auth.password")}
                  type="password"
                  autoComplete="new-password"
                  value={password}
                  onChange={setPassword}
                  hint={t("auth.invitePasswordHint")}
                  error={err?.fields.password}
                />
              </>
            )}
            <Button type="submit" variant="primary" block busy={accept.isPending}>
              {t("auth.acceptInvite")}
            </Button>
          </form>
        </>
      )}
    </AuthLayout>
  );
}
