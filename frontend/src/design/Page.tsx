// SPDX-License-Identifier: AGPL-3.0-only
import * as RTabs from "@radix-ui/react-tabs";
import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "./Button";
import { Dialog, DialogActions } from "./Dialog";
import { Robin, type RobinPose } from "./Robin";

export function PageHeader({ title, lead, actions, children }: { title: ReactNode; lead?: ReactNode; actions?: ReactNode; children?: ReactNode }) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {lead && <p className="muted">{lead}</p>}
        {children}
      </div>
      {actions && <div className="actions">{actions}</div>}
    </header>
  );
}

/**
 * An empty screen is an invitation to act: say what goes here and offer the first action. The
 * robin keeps people company in the ones they meet first, never in money, data or errors.
 */
export function EmptyState({ title, body, action, robin }: { title: ReactNode; body?: ReactNode; action?: ReactNode; robin?: RobinPose }) {
  return (
    <div className="ledger-empty">
      {robin && <Robin pose={robin} width={112} />}
      <h2>{title}</h2>
      {body && <p style={{ marginTop: 4 }}>{body}</p>}
      {action && <div style={{ marginTop: 16 }}>{action}</div>}
    </div>
  );
}

/** Shown while a page or its data loads, so a slow start isn't a blank screen. */
export function PageLoading() {
  const { t } = useTranslation();
  return (
    <div className="page-loading" role="status">
      <span className="spinner" aria-hidden="true" />
      {t("app.loading")}
    </div>
  );
}

export function LoadingRow({ colSpan }: { colSpan: number }) {
  const { t } = useTranslation();
  return (
    <tr>
      <td colSpan={colSpan} className="muted">
        {t("app.loading")}
      </td>
    </tr>
  );
}

interface ConfirmProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  body: ReactNode;
  confirmLabel: ReactNode;
  onConfirm: () => void;
  busy?: boolean;
  danger?: boolean;
}

export function ConfirmDialog({ open, onOpenChange, title, body, confirmLabel, onConfirm, busy, danger = true }: ConfirmProps) {
  const { t } = useTranslation();
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={title}>
      <div>{body}</div>
      <DialogActions>
        <Button onClick={() => onOpenChange(false)}>{t("app.cancel")}</Button>
        <Button variant={danger ? "danger" : "primary"} busy={busy} onClick={onConfirm}>
          {confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** Hook-style helper: returns [dialogElement, ask]. ask(onConfirm) opens the dialog. */
export function useConfirm(opts: { title: ReactNode; body: ReactNode; confirmLabel: ReactNode; danger?: boolean }) {
  const [pending, setPending] = useState<null | (() => void)>(null);
  const element = (
    <ConfirmDialog
      open={pending !== null}
      onOpenChange={(o) => !o && setPending(null)}
      title={opts.title}
      body={opts.body}
      confirmLabel={opts.confirmLabel}
      danger={opts.danger}
      onConfirm={() => {
        pending?.();
        setPending(null);
      }}
    />
  );
  return [element, (fn: () => void) => setPending(() => fn)] as const;
}

export function Tabs({ value, onChange, tabs }: { value: string; onChange: (v: string) => void; tabs: { value: string; label: ReactNode }[] }) {
  return (
    <RTabs.Root value={value} onValueChange={onChange}>
      <RTabs.List className="tabs" aria-label="Sections">
        {tabs.map((tab) => (
          <RTabs.Trigger key={tab.value} value={tab.value} className="tab">
            {tab.label}
          </RTabs.Trigger>
        ))}
      </RTabs.List>
    </RTabs.Root>
  );
}
