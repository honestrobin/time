// SPDX-License-Identifier: AGPL-3.0-only
import type { ReactNode } from "react";
import { Robin, type RobinPose } from "../../design";
import { Wordmark } from "../shell/Wordmark";

/** Sign-in and the pages around it. The robin greets people on the ones that welcome them. */
export function AuthLayout({ robin, children }: { robin?: RobinPose; children: ReactNode }) {
  return (
    <div className="auth">
      <header className="auth-head">
        <Wordmark />
      </header>
      <main className="auth-main" id="main">
        <div className="auth-card">
          {robin && <Robin pose={robin} width={96} className="auth-robin" />}
          {children}
        </div>
      </main>
    </div>
  );
}
