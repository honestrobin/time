// SPDX-License-Identifier: AGPL-3.0-only
// Compact, labelled inputs for editing a value in place inside a ledger row.
import { useEffect, useId, useRef } from "react";
import { DurationInput, MoneyInput } from "../../design";
import type { DurationStyle } from "../../lib/format";

/** Money in minor units; calls onCommit on blur only when the value changed. */
export function CellMoney({
  label,
  value,
  currency,
  placeholder,
  disabled,
  onCommit,
}: {
  label: string;
  value: number | null;
  currency: string;
  placeholder?: string;
  disabled?: boolean;
  onCommit: (v: number | null) => void;
}) {
  const id = useId();
  const commit = useCommit(value, onCommit);
  return (
    <div className="cell-input">
      <label htmlFor={id} className="sr-only">
        {label}
      </label>
      <MoneyInput
        id={id}
        value={value}
        currency={currency}
        placeholder={placeholder}
        disabled={disabled}
        onChange={commit}
      />
    </div>
  );
}

/** Duration in seconds; commits on blur or Enter when the value changed. */
export function CellDuration({
  label,
  value,
  style,
  placeholder,
  disabled,
  onCommit,
}: {
  label: string;
  value: number | null;
  style: DurationStyle;
  placeholder?: string;
  disabled?: boolean;
  onCommit: (v: number | null) => void;
}) {
  const commit = useCommit(value, onCommit);
  return (
    <div className="cell-input">
      <DurationInput
        aria-label={label}
        value={value}
        style={style}
        placeholder={placeholder}
        disabled={disabled}
        onChange={commit}
      />
    </div>
  );
}

/** Enter followed by blur reports the same value twice; send it once. */
function useCommit(value: number | null, onCommit: (v: number | null) => void) {
  const last = useRef(value);
  useEffect(() => {
    last.current = value;
  }, [value]);
  return (v: number | null) => {
    if (v === last.current) return;
    last.current = v;
    onCommit(v);
  };
}
