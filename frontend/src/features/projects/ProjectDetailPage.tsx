// SPDX-License-Identifier: AGPL-3.0-only
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, ConfirmDialog, Tabs, useToast } from "../../design";
import { api, errorInfo, unwrap } from "../../lib/api";
import { ProjectOverview } from "./ProjectOverview";
import { ProjectSettingsTab } from "./ProjectSettingsTab";
import { ProjectTasksTab } from "./ProjectTasksTab";
import { ProjectTeamTab } from "./ProjectTeamTab";
import { projectKeys, projectQuery, useProjectCache } from "./queries";
import { useStaleRatesPrompt } from "./useStaleRatesPrompt";
import "./catalog.css";

type Tab = "overview" | "tasks" | "team" | "settings";

export function ProjectDetailPage() {
  const { t } = useTranslation();
  const { projectId } = useParams({ strict: false }) as { projectId: string };
  const toast = useToast();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const cache = useProjectCache();
  const stale = useStaleRatesPrompt(projectId);
  const [tab, setTab] = useState<Tab>("overview");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const q = useQuery(projectQuery(projectId));
  const project = q.data;

  const archive = useMutation({
    mutationFn: (isActive: boolean) => unwrap(api.PATCH("/api/v1/projects/{id}", { params: { path: { id: projectId } }, body: { is_active: isActive } })),
    onSuccess: (p) => {
      cache(p);
      toast(p.is_active ? t("projects.restored") : t("projects.archived"));
    },
    onError: (e) => toast(errorInfo(e).message, "error"),
  });

  const remove = useMutation({
    mutationFn: () => unwrap(api.DELETE("/api/v1/projects/{id}", { params: { path: { id: projectId } } })),
    onSuccess: () => {
      setConfirmDelete(false);
      qc.removeQueries({ queryKey: projectKeys.detail(projectId) });
      void qc.invalidateQueries({ queryKey: ["projects", "list"] });
      toast(t("projects.deleted"));
      void navigate({ to: "/projects" });
    },
  });

  if (q.isError) {
    return (
      <div className="page page-narrow">
        <p className="notice notice-error" role="alert">
          {errorInfo(q.error).message}
        </p>
        <p style={{ marginTop: 16 }}>
          <Link to="/projects">{t("projects.backToList")}</Link>
        </p>
      </div>
    );
  }
  if (!project) return <div className="page">{t("app.loading")}</div>;

  const tabs: { value: Tab; label: string }[] = [
    { value: "overview", label: t("projects.tabs.overview") },
    { value: "tasks", label: t("projects.tabs.tasks") },
    { value: "team", label: t("projects.tabs.team") },
    { value: "settings", label: t("projects.tabs.settings") },
  ];

  return (
    <div className="page">
      <header className="page-header">
        <div className="project-title">
          <span className="client-name">{project.client.name}</span>
          <h1>
            {project.name}
            {project.code && <span className="code" style={{ fontSize: "var(--text-lg)", fontWeight: 500 }}>{project.code}</span>}
            {!project.is_active && <span className="badge">{t("projects.archivedBadge")}</span>}
          </h1>
        </div>
        <div className="actions">
          <Button onClick={() => archive.mutate(!project.is_active)} busy={archive.isPending}>
            {project.is_active ? t("projects.archive") : t("projects.restore")}
          </Button>
          <Button
            variant="danger"
            onClick={() => {
              remove.reset();
              setConfirmDelete(true);
            }}
          >
            {t("projects.delete")}
          </Button>
        </div>
      </header>

      {!project.is_active && <p className="notice" style={{ marginBottom: 16 }}>{t("projects.archivedNotice")}</p>}

      <Tabs value={tab} onChange={(v) => setTab(v as Tab)} tabs={tabs} />

      {/* Panels stay mounted so unsaved settings survive a look at another tab. */}
      <div role="tabpanel" aria-label={tabs[0].label} hidden={tab !== "overview"}>
        <ProjectOverview project={project} onEditSettings={() => setTab("settings")} />
      </div>
      <div role="tabpanel" aria-label={tabs[1].label} hidden={tab !== "tasks"}>
        <ProjectTasksTab project={project} onRatesChanged={stale.check} />
      </div>
      <div role="tabpanel" aria-label={tabs[2].label} hidden={tab !== "team"}>
        <ProjectTeamTab project={project} onRatesChanged={stale.check} />
      </div>
      <div role="tabpanel" aria-label={tabs[3].label} hidden={tab !== "settings"}>
        <ProjectSettingsTab project={project} onRatesChanged={stale.check} />
      </div>

      <ConfirmDialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        title={t("projects.deleteTitle", { name: project.name })}
        body={
          <div className="stack">
            <p>{t("projects.deleteBody")}</p>
            {remove.error && (
              <p className="notice notice-error" role="alert">
                {errorInfo(remove.error).message}
              </p>
            )}
          </div>
        }
        confirmLabel={t("projects.delete")}
        busy={remove.isPending}
        onConfirm={() => remove.mutate()}
      />
      {stale.element}
    </div>
  );
}
