// SPDX-License-Identifier: AGPL-3.0-only
import { forwardRef, type ButtonHTMLAttributes } from "react";

type Variant = "primary" | "secondary" | "ghost" | "danger";

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: "md" | "sm";
  block?: boolean;
  busy?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "secondary", size = "md", block, busy, className, children, disabled, type = "button", ...rest },
  ref,
) {
  const classes = ["btn", `btn-${variant}`, size === "sm" && "btn-sm", block && "btn-block", className].filter(Boolean).join(" ");
  return (
    <button ref={ref} type={type} className={classes} disabled={disabled || busy} aria-busy={busy || undefined} {...rest}>
      {busy && <span className="spinner" aria-hidden style={{ width: 14, height: 14 }} />}
      {children}
    </button>
  );
});

export function Kbd({ children }: { children: string }) {
  return <kbd className="kbd">{children}</kbd>;
}
