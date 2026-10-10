// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, Outlet, useLocation, useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Menu } from "../../design";
import { api, setAccountId, unwrap } from "../../lib/api";
import { useHotkeys } from "../../lib/hotkeys";
import { useAuthConfig, useMe, usePermissions } from "../../lib/session";
import { AccountStatusBanner, EmailVerificationBanner } from "../settings/AccountData";
import { ReauthDialog } from "../auth/ReauthDialog";
import { TwoFactorGate } from "../settings/TwoFactorGate";
import { LiveUpdates } from "../time/LiveUpdates";
import { RunningTimerTab, TimerPill } from "../time/RunningTimer";
import "../time/time.css";
import { HelpDialog } from "./Help";
import { Wordmark } from "./Wordmark";
import { useTeamShows } from "../team/reach";
import { navSections, shortcutList } from "./nav";

/** The shell: one bar across the top with the menu, Help and Settings; the page below it. */
export function AppShell() {
  const { t } = useTranslation();
  const me = useMe();
  const perms = usePermissions();
  const navigate = useNavigate();
  const pathname = useLocation({ select: (l) => l.pathname });
  const qc = useQueryClient();
  const [showHelp, setShowHelp] = useState(false);
  const current = me.accounts.find((a) => a.id === me.current_account_id);

  const logout = useMutation({
    mutationFn: () => unwrap(api.POST("/api/v1/auth/logout")),
    onSettled: () => {
      qc.clear();
      location.assign("/login");
    },
  });

  const authConfig = useAuthConfig();
  // The team shows while the account has other people, even with its switch off (team/reach).
  const team = useTeamShows();
  const features = authConfig?.features ?? [];
  const sections = navSections(perms, authConfig?.edition, team && !features.includes("team") ? [...features, "team"] : features);
  const hotkeys: Record<string, () => void> = { "?": () => setShowHelp(true) };
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

  // The work pages go in the bar; the settings pages behind Settings, with the account and sign-out.
  const pages = sections.filter((s) => s.id !== "settings").flatMap((s) => s.items);
  const settingsPages = sections.find((s) => s.id === "settings")?.items ?? [];
  const settingsMenu: Parameters<typeof Menu>[0]["items"] = [
    { heading: current?.name ?? "—" },
    ...settingsPages.map((item) => ({ label: t(item.label), onSelect: () => void navigate({ to: item.to }) })),
    ...(me.accounts.length > 1
      ? [
          "separator" as const,
          { heading: t("nav.switchAccount") },
          ...me.accounts.map((a) => ({ label: a.id === current?.id ? `✓ ${a.name}` : a.name, onSelect: () => void switchTo(a.id) })),
        ]
      : []),
    "separator" as const,
    { label: t("nav.signOut"), onSelect: () => logout.mutate() },
  ];

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        {t("nav.skipToContent")}
      </a>
      <header className="topbar">
        <Wordmark short />
        <nav id="app-nav" className="topnav" aria-label={t("nav.mainNavigation")}>
          {pages.map((item) => {
            const alsoActive = item.alsoActive?.some((p) => pathname.startsWith(p));
            return (
              <Link
                key={item.to}
                to={item.to}
                className={alsoActive ? "topnav-link active" : "topnav-link"}
                activeProps={{ className: "active" }}
                aria-current={alsoActive ? "page" : undefined}
              >
                {t(item.label)}
              </Link>
            );
          })}
        </nav>
        <div className="topbar-end">
          {!me.two_factor_setup_required && <TimerPill />}
          {/* A person is one step away from every page: Help is in the bar, and behind "?". */}
          <button type="button" className="help-button" aria-label={t("help.title")} aria-haspopup="dialog" onClick={() => setShowHelp(true)}>
            ?
          </button>
          <Menu
            align="end"
            trigger={
              <button type="button" className="settings-button" aria-label={`${t("nav.settings")}, ${current?.name ?? ""}`}>
                <span className="avatar" aria-hidden>
                  {initials(me.name)}
                </span>
                <span className="settings-label">{t("nav.settings")}</span>
              </button>
            }
            items={settingsMenu}
          />
        </div>
      </header>
      <div className="main">
        <EmailVerificationBanner />
        <AccountStatusBanner />
        {!me.two_factor_setup_required && (
          <>
            <LiveUpdates />
            <RunningTimerTab />
          </>
        )}
        <main id="main" tabIndex={-1}>
          {me.two_factor_setup_required ? <TwoFactorGate /> : <Outlet />}
        </main>
      </div>
      <ReauthDialog />
      <HelpDialog open={showHelp} onOpenChange={setShowHelp} shortcuts={shortcutList(sections)} canExport={perms.isAdmin} />
    </div>
  );
}

/** "Marta Owner" → "MO": the person's initials, for the round badge on Settings. */
export function initials(name: string | undefined): string {
  const parts = (name ?? "").trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  const first = [...parts[0]][0] ?? "";
  const last = parts.length > 1 ? ([...parts[parts.length - 1]][0] ?? "") : "";
  return (first + last).toUpperCase();
}
