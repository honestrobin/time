// SPDX-License-Identifier: AGPL-3.0-only
import { createRootRoute, createRoute, createRouter, Outlet, redirect } from "@tanstack/react-router";
import { LoginPage } from "../features/auth/LoginPage";
import { ForgotPasswordPage, MagicLinkPage, ResetPasswordPage, VerifyEmailPage } from "../features/auth/RecoveryPages";
import { SignupPage } from "../features/auth/SignupPage";
import { AccountSettingsPage } from "../features/settings/AccountSettingsPage";
import { AuditLogPage } from "../features/settings/AuditLogPage";
import { ProfilePage } from "../features/settings/ProfilePage";
import { ApprovalsPage } from "../features/approvals/ApprovalsPage";
import { InvitePage } from "../features/auth/InvitePage";
import { ClientsPage } from "../features/clients/ClientsPage";
import { ExpensesPage } from "../features/expenses/ExpensesPage";
import { ProjectDetailPage } from "../features/projects/ProjectDetailPage";
import { ProjectNewPage } from "../features/projects/ProjectNewPage";
import { ProjectsPage } from "../features/projects/ProjectsPage";
import { ExpenseCategoriesPage } from "../features/settings/ExpenseCategoriesPage";
import { AppShell } from "../features/shell/AppShell";
import { TasksPage } from "../features/tasks/TasksPage";
import { PersonPage } from "../features/team/PersonPage";
import { TeamPage } from "../features/team/TeamPage";
import { TimePage } from "../features/time/TimePage";
import { ApiError, getAccountId, queryClient } from "../lib/api";
import { sendPageView } from "../lib/analytics";
import { authConfigQuery, meQuery } from "../lib/session";
import { ImportPage } from "../features/import/ImportPage";
import { InvoiceNewPage } from "../features/invoices/InvoiceNewPage";
import { InvoicePage } from "../features/invoices/InvoicePage";
import { InvoiceSettingsPage } from "../features/invoices/InvoiceSettingsPage";
import { DevelopersPage } from "../features/developers/DevelopersPage";
import { ReportsPage } from "../features/reports/ReportsPage";
import { AccountingPage } from "../features/settings/AccountingPage";
import { DevicePage } from "../features/settings/DevicePage";
import { BillingPage } from "../features/settings/BillingPage";
import { validateReportSearch } from "../features/reports/search";
import { InvoicesPage } from "../features/invoices/InvoicesPage";
import { PaymentsSettingsPage } from "../features/invoices/PaymentsSettingsPage";
import { PublicInvoicePage } from "../features/invoices/PublicInvoicePage";

const rootRoute = createRootRoute({
  component: Outlet,
  beforeLoad: () => queryClient.ensureQueryData(authConfigQuery),
});

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/login",
  component: LoginPage,
  validateSearch: (s: Record<string, unknown>): { next?: string } => (typeof s.next === "string" ? { next: s.next } : {}),
  beforeLoad: async () => {
    const config = await queryClient.ensureQueryData(authConfigQuery);
    if (config.needs_setup) throw redirect({ to: "/signup" });
  },
});

/** Everything behind sign-in. */
const appRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: "app",
  component: AppShell,
  beforeLoad: async ({ location }) => {
    const config = await queryClient.ensureQueryData(authConfigQuery);
    if (config.needs_setup) throw redirect({ to: "/signup" });
    try {
      await queryClient.ensureQueryData(meQuery);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) throw redirect({ to: "/login", search: { next: location.href } });
      throw e;
    }
  },
});

const indexRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/",
  beforeLoad: () => {
    throw redirect({ to: "/time" });
  },
});

const signupRoute = createRoute({ getParentRoute: () => rootRoute, path: "/signup", component: SignupPage });
const forgotRoute = createRoute({ getParentRoute: () => rootRoute, path: "/forgot", component: ForgotPasswordPage });
const magicRoute = createRoute({ getParentRoute: () => rootRoute, path: "/auth/magic", component: MagicLinkPage });
const resetRoute = createRoute({ getParentRoute: () => rootRoute, path: "/auth/reset", component: ResetPasswordPage });
const inviteRoute = createRoute({ getParentRoute: () => rootRoute, path: "/auth/invite", component: InvitePage });
const verifyRoute = createRoute({ getParentRoute: () => rootRoute, path: "/auth/verify", component: VerifyEmailPage });

const timeRoute = createRoute({ getParentRoute: () => appRoute, path: "/time", component: TimePage });
const timeDayRoute = createRoute({ getParentRoute: () => appRoute, path: "/time/day/$date", component: TimePage });
const timeWeekRoute = createRoute({ getParentRoute: () => appRoute, path: "/time/week/$date", component: TimePage });
const expensesRoute = createRoute({ getParentRoute: () => appRoute, path: "/expenses", component: ExpensesPage });
const projectsRoute = createRoute({ getParentRoute: () => appRoute, path: "/projects", component: ProjectsPage });
const projectNewRoute = createRoute({ getParentRoute: () => appRoute, path: "/projects/new", component: ProjectNewPage });
const projectRoute = createRoute({ getParentRoute: () => appRoute, path: "/projects/$projectId", component: ProjectDetailPage });
const clientsRoute = createRoute({ getParentRoute: () => appRoute, path: "/clients", component: ClientsPage });
const tasksRoute = createRoute({ getParentRoute: () => appRoute, path: "/tasks", component: TasksPage });
const teamRoute = createRoute({ getParentRoute: () => appRoute, path: "/team", component: TeamPage });
const personRoute = createRoute({ getParentRoute: () => appRoute, path: "/team/$personId", component: PersonPage });
const approvalsRoute = createRoute({ getParentRoute: () => appRoute, path: "/approvals", component: ApprovalsPage });
const categoriesRoute = createRoute({ getParentRoute: () => appRoute, path: "/settings/expense-categories", component: ExpenseCategoriesPage });
const accountSettingsRoute = createRoute({ getParentRoute: () => appRoute, path: "/settings/account", component: AccountSettingsPage });
const profileRoute = createRoute({ getParentRoute: () => appRoute, path: "/settings/profile", component: ProfilePage });
const auditRoute = createRoute({ getParentRoute: () => appRoute, path: "/settings/audit", component: AuditLogPage });
const importRoute = createRoute({ getParentRoute: () => appRoute, path: "/settings/import", component: ImportPage });
const invoicesRoute = createRoute({ getParentRoute: () => appRoute, path: "/invoices", component: InvoicesPage });
const invoiceNewRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/invoices/new",
  component: InvoiceNewPage,
  // Prefilled from the uninvoiced report.
  validateSearch: (s: Record<string, unknown>): { client?: string; from?: string; to?: string } => {
    const date = (v: unknown) => (typeof v === "string" && /^\d{4}-\d{2}-\d{2}$/.test(v) ? v : undefined);
    return Object.fromEntries(
      Object.entries({ client: typeof s.client === "string" ? s.client : undefined, from: date(s.from), to: date(s.to) }).filter(([, v]) => v !== undefined),
    );
  },
});
const developersRoute = createRoute({ getParentRoute: () => appRoute, path: "/developers", component: DevelopersPage });
const deviceRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/device",
  component: DevicePage,
  validateSearch: (s: Record<string, unknown>): { code?: string } => (typeof s.code === "string" ? { code: s.code } : {}),
});
const reportsRoute = createRoute({ getParentRoute: () => appRoute, path: "/reports", component: ReportsPage, validateSearch: validateReportSearch });
const invoiceRoute = createRoute({ getParentRoute: () => appRoute, path: "/invoices/$invoiceId", component: InvoicePage });
const invoiceSettingsRoute = createRoute({ getParentRoute: () => appRoute, path: "/settings/invoices", component: InvoiceSettingsPage });
const accountingRoute = createRoute({ getParentRoute: () => appRoute, path: "/settings/accounting", component: AccountingPage });
const billingRoute = createRoute({ getParentRoute: () => appRoute, path: "/settings/billing", component: BillingPage });
const paymentsSettingsRoute = createRoute({ getParentRoute: () => appRoute, path: "/settings/payments", component: PaymentsSettingsPage });
const publicInvoiceRoute = createRoute({ getParentRoute: () => rootRoute, path: "/i/$token", component: PublicInvoicePage });

const routeTree = rootRoute.addChildren([
  loginRoute,
  signupRoute,
  forgotRoute,
  magicRoute,
  resetRoute,
  inviteRoute,
  verifyRoute,
  publicInvoiceRoute,
  appRoute.addChildren([
    indexRoute,
    timeRoute,
    timeDayRoute,
    timeWeekRoute,
    expensesRoute,
    projectsRoute,
    projectNewRoute,
    projectRoute,
    clientsRoute,
    tasksRoute,
    teamRoute,
    personRoute,
    approvalsRoute,
    accountSettingsRoute,
    categoriesRoute,
    profileRoute,
    auditRoute,
    importRoute,
    reportsRoute,
    developersRoute,
    deviceRoute,
    invoicesRoute,
    invoiceNewRoute,
    invoiceRoute,
    invoiceSettingsRoute,
    paymentsSettingsRoute,
    billingRoute,
    accountingRoute,
  ]),
]);

export const router = createRouter({ routeTree, defaultPreload: "intent" });

// Page views (Honest Robin Cloud only: the server hands out no key elsewhere).
router.subscribe("onResolved", () => {
  const match = router.state.matches.at(-1);
  if (!match) return;
  const route = (router.routesById as Record<string, { fullPath?: string } | undefined>)[match.routeId]?.fullPath ?? "/";
  sendPageView(queryClient.getQueryData(authConfigQuery.queryKey)?.analytics, getAccountId(), route);
});

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
