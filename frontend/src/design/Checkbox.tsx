// SPDX-License-Identifier: AGPL-3.0-only
import * as RCheckbox from "@radix-ui/react-checkbox";
import { useId, type ReactNode } from "react";

interface CheckboxProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: ReactNode;
  hint?: ReactNode;
  disabled?: boolean;
}

export function Checkbox({ checked, onChange, label, hint, disabled }: CheckboxProps) {
  const id = useId();
  return (
    <div className="checkbox-row">
      <RCheckbox.Root id={id} className="checkbox" checked={checked} onCheckedChange={(v) => onChange(v === true)} disabled={disabled}>
        <RCheckbox.Indicator aria-hidden>✓</RCheckbox.Indicator>
      </RCheckbox.Root>
      <label htmlFor={id}>
        <span>{label}</span>
        {hint && <span className="field-hint" style={{ display: "block" }}>{hint}</span>}
      </label>
    </div>
  );
}
