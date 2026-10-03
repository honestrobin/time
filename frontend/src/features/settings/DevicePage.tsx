// SPDX-License-Identifier: AGPL-3.0-only
// Approving a device's sign-in (the browser extension, RFC 8628): the person checks the code
// matches the one on the device, then lets it act as them in the current account.
import { useMutation, useQuery } from "@tanstack/react-query";
import { useSearch } from "@tanstack/react-router";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, PageHeader, TextField } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { useMe } from "../../lib/session";

export function DevicePage() {
  const { t } = useTranslation();
  const search = useSearch({ strict: false }) as { code?: string };
  const [code, setCode] = useState(search.code ?? "");
  const [entered, setEntered] = useState(search.code ?? "");
  const me = useMe();
  const account = me.accounts.find((a) => a.id === me.current_account_id);
  const lookup = useQuery({
    queryKey: ["device", entered],
    queryFn: () => unwrap(api.GET("/api/v1/device_authorizations/{userCode}", { params: { path: { userCode: entered } } })),
    enabled: entered.length > 0,
    retry: false,
  });
  const approve = useMutation({ mutationFn: () => unwrap(api.POST("/api/v1/device_authorizations/{userCode}/approve", { params: { path: { userCode: entered } } })) });
  const deny = useMutation({ mutationFn: () => unwrap(api.POST("/api/v1/device_authorizations/{userCode}/deny", { params: { path: { userCode: entered } } })) });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setEntered(code.trim());
  };

  let body;
  if (approve.isSuccess) {
    body = <p className="notice notice-ok">{t("device.approved", { name: lookup.data?.client_name })}</p>;
  } else if (deny.isSuccess) {
    body = <p className="notice">{t("device.denied")}</p>;
  } else if (!entered || lookup.isError) {
    body = (
      <form className="stack" onSubmit={submit}>
        {lookup.isError && <p className="notice notice-error">{t("device.invalid")}</p>}
        <TextField label={t("device.enterCode")} value={code} onChange={setCode} autoFocus autoComplete="off" placeholder="BCDF-GHJK" />
        <div>
          <Button type="submit" variant="primary" disabled={!code.trim()}>
            {t("device.continue")}
          </Button>
        </div>
      </form>
    );
  } else if (lookup.data) {
    const err = approve.error ?? deny.error;
    body = (
      <div className="stack">
        <p>{t("device.asks", { name: lookup.data.client_name, account: account?.name ?? "" })}</p>
        <p className="device-code" aria-label={t("device.codeLabel")}>
          {lookup.data.user_code}
        </p>
        <p className="muted">{t("device.checkCode")}</p>
        {me.accounts.length > 1 && <p className="muted small">{t("device.otherAccount")}</p>}
        {err && <p className="notice notice-error">{errorInfo(err).message}</p>}
        <div className="row">
          <Button variant="primary" onClick={() => approve.mutate()} busy={approve.isPending}>
            {t("device.approve")}
          </Button>
          <Button onClick={() => deny.mutate()} busy={deny.isPending}>
            {t("device.deny")}
          </Button>
        </div>
      </div>
    );
  } else {
    body = <p className="muted">{t("app.loading")}</p>;
  }
  return (
    <div className="page page-narrow">
      <PageHeader title={t("device.title")} lead={t("device.lead")} />
      {body}
    </div>
  );
}
