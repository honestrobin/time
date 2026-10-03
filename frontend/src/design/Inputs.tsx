// SPDX-License-Identifier: AGPL-3.0-only
import { useEffect, useState, type ReactNode } from "react";
import { formatDuration, minorToInput, parseDuration, parseMoney, type DurationStyle } from "../lib/format";
import { Field } from "./Field";

interface DurationInputProps {
  /** Seconds, or null when empty. */
  value: number | null;
  onChange: (seconds: number | null) => void;
  style?: DurationStyle;
  id?: string;
  placeholder?: string;
  className?: string;
  disabled?: boolean;
  autoFocus?: boolean;
  "aria-label"?: string;
  "aria-invalid"?: boolean;
  "aria-describedby"?: string;
  onEnter?: () => void;
}

/**
 * Text field for durations. Accepts "1:30", "1.5", "1,5", "90m" or "1h 30m"; reformats on blur.
 * Invalid input keeps the text and reports null plus aria-invalid.
 */
export function DurationInput({ value, onChange, style = "hm", onEnter, className, ...rest }: DurationInputProps) {
  const [text, setText] = useState(value === null ? "" : formatDuration(value, style));
  const [invalid, setInvalid] = useState(false);
  useEffect(() => {
    setText(value === null ? "" : formatDuration(value, style));
  }, [value, style]);
  return (
    <input
      {...rest}
      className={["input input-num", className].filter(Boolean).join(" ")}
      inputMode="decimal"
      value={text}
      aria-invalid={invalid || rest["aria-invalid"] || undefined}
      onChange={(e) => {
        setText(e.target.value);
        const parsed = parseDuration(e.target.value);
        setInvalid(parsed === null);
      }}
      onBlur={() => {
        const parsed = parseDuration(text);
        if (parsed === null) return;
        setInvalid(false);
        onChange(text.trim() === "" ? null : parsed);
        setText(text.trim() === "" ? "" : formatDuration(parsed, style));
      }}
      onKeyDown={(e) => {
        if (e.key === "Enter") {
          const parsed = parseDuration(text);
          if (parsed !== null) onChange(text.trim() === "" ? null : parsed);
          onEnter?.();
        }
      }}
    />
  );
}

interface MoneyInputProps {
  /** Minor units, or null when empty. */
  value: number | null;
  onChange: (minor: number | null) => void;
  currency: string;
  id?: string;
  disabled?: boolean;
  placeholder?: string;
  "aria-invalid"?: boolean;
  "aria-describedby"?: string;
}

/** Amount field in major units (12.50), stored as minor units (1250). Shows the currency code. */
export function MoneyInput({ value, onChange, currency, ...rest }: MoneyInputProps) {
  const [text, setText] = useState(minorToInput(value, currency));
  useEffect(() => {
    setText(minorToInput(value, currency));
  }, [value, currency]);
  return (
    <div className="input-affix">
      <input
        {...rest}
        className="input input-num"
        inputMode="decimal"
        value={text}
        onChange={(e) => setText(e.target.value)}
        onBlur={() => {
          const parsed = parseMoney(text, currency);
          onChange(parsed);
          setText(minorToInput(parsed, currency));
        }}
      />
      <span className="input-affix-label">{currency}</span>
    </div>
  );
}

export function DurationField(props: DurationInputProps & { label: ReactNode; hint?: ReactNode; error?: string }) {
  const { label, hint, error, ...rest } = props;
  return (
    <Field label={label} hint={hint} error={error}>
      {(a11y) => <DurationInput {...rest} {...a11y} />}
    </Field>
  );
}

export function MoneyField(props: MoneyInputProps & { label: ReactNode; hint?: ReactNode; error?: string; className?: string }) {
  const { label, hint, error, className, ...rest } = props;
  return (
    <Field label={label} hint={hint} error={error} className={className}>
      {(a11y) => <MoneyInput {...rest} {...a11y} />}
    </Field>
  );
}
