// SPDX-License-Identifier: AGPL-3.0-only
import { forwardRef, useId, type InputHTMLAttributes, type ReactNode, type TextareaHTMLAttributes } from "react";

interface FieldProps {
  label: ReactNode;
  hint?: ReactNode;
  error?: string;
  className?: string;
  children: (props: { id: string; "aria-invalid"?: boolean; "aria-describedby"?: string }) => ReactNode;
}

/** Label + control + hint/error, wired up for screen readers. */
export function Field({ label, hint, error, className, children }: FieldProps) {
  const id = useId();
  const described = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div className={["field", className].filter(Boolean).join(" ")}>
      <label className="field-label" htmlFor={id}>
        {label}
      </label>
      {children({ id, "aria-invalid": error ? true : undefined, "aria-describedby": described })}
      {error ? (
        <span id={`${id}-error`} className="field-error">
          {error}
        </span>
      ) : hint ? (
        <span id={`${id}-hint`} className="field-hint">
          {hint}
        </span>
      ) : null}
    </div>
  );
}

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "onChange"> {
  label: ReactNode;
  hint?: ReactNode;
  error?: string;
  onChange?: (value: string) => void;
  fieldClassName?: string;
}

export const TextField = forwardRef<HTMLInputElement, TextFieldProps>(function TextField(
  { label, hint, error, onChange, className, fieldClassName, ...rest },
  ref,
) {
  return (
    <Field label={label} hint={hint} error={error} className={fieldClassName}>
      {(a11y) => (
        <input ref={ref} className={["input", className].filter(Boolean).join(" ")} onChange={(e) => onChange?.(e.target.value)} {...a11y} {...rest} />
      )}
    </Field>
  );
});

export interface TextAreaFieldProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, "onChange"> {
  label: ReactNode;
  hint?: ReactNode;
  error?: string;
  onChange?: (value: string) => void;
  fieldClassName?: string;
}

export function TextAreaField({ label, hint, error, onChange, fieldClassName, ...rest }: TextAreaFieldProps) {
  return (
    <Field label={label} hint={hint} error={error} className={fieldClassName}>
      {(a11y) => <textarea className="textarea" onChange={(e) => onChange?.(e.target.value)} {...a11y} {...rest} />}
    </Field>
  );
}
