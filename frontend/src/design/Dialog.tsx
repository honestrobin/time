// SPDX-License-Identifier: AGPL-3.0-only
import * as RDialog from "@radix-ui/react-dialog";
import type { ReactNode } from "react";

interface DialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  wide?: boolean;
  children: ReactNode;
}

export function Dialog({ open, onOpenChange, title, description, wide, children }: DialogProps) {
  return (
    <RDialog.Root open={open} onOpenChange={onOpenChange}>
      <RDialog.Portal>
        <RDialog.Overlay className="dialog-overlay" />
        <RDialog.Content className={["dialog-content", wide && "wide"].filter(Boolean).join(" ")} aria-describedby={description ? undefined : undefined}>
          <RDialog.Title className="dialog-title">{title}</RDialog.Title>
          {description ? (
            <RDialog.Description className="muted" style={{ marginTop: -8, marginBottom: 16 }}>
              {description}
            </RDialog.Description>
          ) : (
            <RDialog.Description className="sr-only">{title}</RDialog.Description>
          )}
          {children}
        </RDialog.Content>
      </RDialog.Portal>
    </RDialog.Root>
  );
}

export function DialogActions({ children }: { children: ReactNode }) {
  return <div className="dialog-actions">{children}</div>;
}
