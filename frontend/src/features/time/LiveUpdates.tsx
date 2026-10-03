// SPDX-License-Identifier: AGPL-3.0-only
import { useQueryClient } from "@tanstack/react-query";
import { useCallback } from "react";
import { useLiveTimeEntries } from "../../lib/live";

/** Refreshes the timer and the entries when they change elsewhere (extension, another tab). */
export function LiveUpdates() {
  const qc = useQueryClient();
  const refresh = useCallback(() => {
    void qc.invalidateQueries({ queryKey: ["timer"] });
    void qc.invalidateQueries({ queryKey: ["time_entries"] });
    void qc.invalidateQueries({ queryKey: ["week"] });
  }, [qc]);
  useLiveTimeEntries(refresh);
  return null;
}
