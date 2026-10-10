// SPDX-License-Identifier: AGPL-3.0-only
import { Robin } from "../../design";

/** The robin and the name. In the app's own bar, [short]: the product's name alone. */
export function Wordmark({ short = false }: { short?: boolean }) {
  return (
    <a className="wordmark" href="/" aria-label="Honest Robin: Time">
      <Robin pose="face" width={28} />
      {short ? (
        "Time"
      ) : (
        <>
          Honest Robin <span>Time</span>
        </>
      )}
    </a>
  );
}
