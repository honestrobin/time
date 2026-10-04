// SPDX-License-Identifier: AGPL-3.0-only
import { Robin } from "../../design";

export function Wordmark() {
  return (
    <a className="wordmark" href="/" aria-label="Honest Robin: Time">
      <Robin pose="face" width={28} />
      Honest Robin <span>Time</span>
    </a>
  );
}
