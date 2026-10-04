// SPDX-License-Identifier: AGPL-3.0-only
import { act, fireEvent, render } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { MoneyInput } from "./Inputs";

// A form that keeps the amount in its own state, the way ProjectForm and InvoicePage do.
function Form({ seen }: { seen: (number | null)[] }) {
  const [value, setValue] = useState<number | null>(null);
  return (
    <MoneyInput
      id="rate"
      currency="GBP"
      value={value}
      onChange={(v) => {
        seen.push(v);
        setValue(v);
      }}
    />
  );
}

describe("MoneyInput", () => {
  it("reports the amount while it's typed, before the field loses focus", () => {
    const seen: (number | null)[] = [];
    const { container } = render(<Form seen={seen} />);
    const input = container.querySelector("input")!;
    act(() => {
      input.focus();
      fireEvent.change(input, { target: { value: "8" } });
      fireEvent.change(input, { target: { value: "85" } });
    });
    expect(seen.at(-1)).toBe(8500);
    // The form's echo must not reformat what's being typed.
    expect(input.value).toBe("85");
  });

  it("tidies the text when the field loses focus", () => {
    const seen: (number | null)[] = [];
    const { container } = render(<Form seen={seen} />);
    const input = container.querySelector("input")!;
    act(() => {
      fireEvent.change(input, { target: { value: "12.5" } });
      fireEvent.blur(input);
    });
    expect(seen.at(-1)).toBe(1250);
    expect(input.value).toBe("12.50");
  });

  it("reports an emptied field as no amount", () => {
    const seen: (number | null)[] = [];
    const { container } = render(<Form seen={seen} />);
    const input = container.querySelector("input")!;
    act(() => {
      fireEvent.change(input, { target: { value: "40" } });
      fireEvent.change(input, { target: { value: "" } });
    });
    expect(seen.at(-1)).toBeNull();
  });
});
