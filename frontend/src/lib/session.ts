// SPDX-License-Identifier: AGPL-3.0-only
import { queryOptions, useQuery } from "@tanstack/react-query";
import { api, getAccountId, setAccountId, unwrap, type Schemas } from "./api";

export type Me = Schemas["MeView"];
export type AuthConfig = Schemas["AuthConfig"];

export const authConfigQuery = queryOptions({
  queryKey: ["auth-config"],
  queryFn: () => unwrap(api.GET("/api/v1/auth/config")),
  staleTime: 60_000,
});

export const meQuery = queryOptions({
  queryKey: ["me"],
  queryFn: async (): Promise<Me> => {
    let me = await unwrap(api.GET("/api/v1/me"));
    // Pick an account: the stored one if still valid, otherwise the first.
    const stored = getAccountId();
    const valid = me.accounts.find((a) => a.id === stored);
    if (!valid && me.accounts.length > 0) {
      setAccountId(me.accounts[0].id);
      me = await unwrap(api.GET("/api/v1/me"));
    } else if (me.accounts.length === 0) {
      setAccountId(null);
    }
    return me;
  },
  staleTime: 60_000,
});

export function useMe(): Me {
  const { data } = useQuery(meQuery);
  if (!data) throw new Error("useMe() used outside the authenticated app");
  return data;
}

export function useAuthConfig() {
  return useQuery(authConfigQuery).data;
}

export function usePermissions() {
  const me = useMe();
  const role = me.role ?? "member";
  return {
    role,
    isAdmin: role === "admin",
    isManager: role === "manager",
    isManagerOrAdmin: role === "admin" || role === "manager",
    canSeeRates: me.permissions?.can_see_rates ?? false,
    canManageProjects: me.permissions?.can_manage_projects ?? false,
    canManageInvoices: me.permissions?.can_manage_invoices ?? false,
  };
}
