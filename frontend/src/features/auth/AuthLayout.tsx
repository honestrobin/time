// SPDX-License-Identifier: AGPL-3.0-only
import type { ReactNode } from "react";
import { Wordmark } from "../shell/Wordmark";

export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="auth">
      <header className="auth-head">
        <Wordmark />
      </header>
      <main className="auth-main" id="main">
        <div className="auth-card">{children}</div>
      </main>
    </div>
  );
}
