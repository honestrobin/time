// SPDX-License-Identifier: AGPL-3.0-only
import * as RMenu from "@radix-ui/react-dropdown-menu";
import type { ReactNode } from "react";

export interface MenuItem {
  label: ReactNode;
  onSelect: () => void;
  danger?: boolean;
  disabled?: boolean;
}

interface MenuProps {
  trigger: ReactNode;
  items: (MenuItem | "separator" | { heading: ReactNode })[];
  align?: "start" | "end";
}

export function Menu({ trigger, items, align = "end" }: MenuProps) {
  return (
    <RMenu.Root>
      <RMenu.Trigger asChild>{trigger}</RMenu.Trigger>
      <RMenu.Portal>
        <RMenu.Content className="menu-content" align={align} sideOffset={4}>
          {items.map((item, i) =>
            item === "separator" ? (
              <RMenu.Separator key={i} className="menu-separator" />
            ) : "heading" in item ? (
              <RMenu.Label key={i} className="menu-label">
                {item.heading}
              </RMenu.Label>
            ) : (
              <RMenu.Item
                key={i}
                className="menu-item"
                disabled={item.disabled}
                onSelect={item.onSelect}
                style={item.danger ? { color: "var(--signal)" } : undefined}
              >
                {item.label}
              </RMenu.Item>
            ),
          )}
        </RMenu.Content>
      </RMenu.Portal>
    </RMenu.Root>
  );
}
