// SPDX-License-Identifier: AGPL-3.0-only
import * as RSelect from "@radix-ui/react-select";
import type { ReactNode } from "react";
import { Field } from "./Field";

export interface Option<T extends string = string> {
  value: T;
  label: ReactNode;
}

interface SelectProps<T extends string> {
  value: T | undefined;
  onChange: (value: T) => void;
  options: Option<T>[];
  placeholder?: string;
  id?: string;
  disabled?: boolean;
  "aria-label"?: string;
  "aria-invalid"?: boolean;
  "aria-describedby"?: string;
}

// Radix Select cannot represent an empty value, so "none" options use this sentinel.
export const NONE = "__none__";

export function Select<T extends string>({ value, onChange, options, placeholder, ...rest }: SelectProps<T>) {
  return (
    <RSelect.Root value={value} onValueChange={(v) => onChange(v as T)} disabled={rest.disabled}>
      <RSelect.Trigger className="select-trigger" id={rest.id} aria-label={rest["aria-label"]} aria-invalid={rest["aria-invalid"]} aria-describedby={rest["aria-describedby"]}>
        <RSelect.Value placeholder={placeholder} />
        <RSelect.Icon aria-hidden>▾</RSelect.Icon>
      </RSelect.Trigger>
      <RSelect.Portal>
        <RSelect.Content className="select-content" position="popper" sideOffset={4}>
          <RSelect.Viewport>
            {options.map((o) => (
              <RSelect.Item key={o.value} value={o.value} className="select-item">
                <RSelect.ItemText>{o.label}</RSelect.ItemText>
              </RSelect.Item>
            ))}
          </RSelect.Viewport>
        </RSelect.Content>
      </RSelect.Portal>
    </RSelect.Root>
  );
}

interface SelectFieldProps<T extends string> extends Omit<SelectProps<T>, "id"> {
  label: ReactNode;
  hint?: ReactNode;
  error?: string;
  className?: string;
}

export function SelectField<T extends string>({ label, hint, error, className, ...rest }: SelectFieldProps<T>) {
  return (
    <Field label={label} hint={hint} error={error} className={className}>
      {(a11y) => <Select {...rest} {...a11y} />}
    </Field>
  );
}
