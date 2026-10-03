// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, Outlet, useNavigate } from "@tanstack/react-router";
import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Dialog, Kbd, Menu } from "../../design";
import { api, setAccountId, unwrap } from "../../lib/api";
import { useHotkeys } from "../../lib/hotkeys";
import { useAuthConfig, useMe, usePermissions } from "../../lib/session";
import { AccountStatusBanner, EmailVerificationBanner } from "../settings/AccountData";
import { ReauthDialog } from "../auth/ReauthDialog";
import { TwoFactorGate } from "../settings/TwoFactorGate";
import { LiveUpdates } from "../time/LiveUpdates";
import { TimerStrip } from "../time/TimerStrip";
import "../time/time.css";
import { Wordmark } from "./Wordmark";
import { navSections, shortcutList } from "./nav";

export function AppShell() {
  const { t } = useTranslation();
  const me = useMe();
  const perms = usePermissions();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [showShortcuts, setShowShortcuts] = useState(false);
  const current = me.accounts.find((a) => a.id === me.current_account_id);

  const logout = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/auth/logout")),
    onSettled: () => {
      qc.clear();
      location.assign("/login");
    },
  });

  const authConfig = useAuthConfig();
  const sections = navSections(perms, authConfig?.edition);
  const hotkeys: Record<string, () => void> = { "?": () => setShowShortcuts(true) };
  sections.flatMap((s) => s.items).forEach((item) => {
    if (item.key) hotkeys[item.key] = () => void navigate({ to: item.to });
  });
  useHotkeys(hotkeys);

  const switchTo = async (id: string) => {
    setAccountId(id);
    qc.clear();
    await navigate({ to: "/" });
    location.reload();
  };

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <aside className="sidebar">
        <Wordmark />
        <nav className="nav" aria-label={t("nav.mainNavigation")}>
          {sections.map((section) => (
            <NavSection key={section.id} heading={section.heading ? t(section.heading) : undefined}>
              {section.items.map((item) => (
                <Link key={item.to} to={item.to} className="nav-link" activeProps={{ className: "nav-link active" }}>
                  <span>{t(item.label)}</span>
                  {item.key && <Kbd>{item.key.toUpperCase()}</Kbd>}
                </Link>
              ))}
            </NavSection>
          ))}
        </nav>
        <div className="sidebar-foot">
          <Menu
            align="start"
            trigger={
              <button className="account-button" type="button">
                <strong>{current?.name ?? "—"}</strong>
                <span>{me.name}</span>
              </button>
            }
            items={[
              ...(me.accounts.length > 1
                ? [
                    { heading: t("nav.switchAccount") },
                    ...me.accounts.map((a) => ({ label: a.id === current?.id ? `✓ ${a.name}` : a.name, onSelect: () => void switchTo(a.id) })),
                    "separator" as const,
                  ]
                : []),
              { label: t("nav.profile"), onSelect: () => void navigate({ to: "/settings/profile" }) },
              { label: t("nav.shortcuts"), onSelect: () => setShowShortcuts(true) },
              "separator" as const,
              { label: t("nav.signOut"), onSelect: () => logout.mutate() },
            ]}
          />
        </div>
      </aside>
      <div className="main">
        <EmailVerificationBanner />
        <AccountStatusBanner />
        {!me.two_factor_setup_required && (
          <>
            <LiveUpdates />
            <TimerStrip />
          </>
        )}
        <main id="main" tabIndex={-1}>
          {me.two_factor_setup_required ? <TwoFactorGate /> : <Outlet />}
        </main>
      </div>
      <ReauthDialog />
      <Dialog open={showShortcuts} onOpenChange={setShowShortcuts} title={t("shortcuts.title")}>
        <table className="ledger">
          <tbody>
            {shortcutList(sections).map((s) => (
              <tr key={s.key}>
                <td style={{ width: 80 }}>
                  <Kbd>{s.key}</Kbd>
                </td>
                <td>{t(s.label)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Dialog>
    </div>
  );
}

function NavSection({ heading, children }: { heading?: string; children: ReactNode }) {
  return (
    <>
      {heading && <div className="nav-heading">{heading}</div>}
      {children}
    </>
  );
}
