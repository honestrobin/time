// SPDX-License-Identifier: AGPL-3.0-only
import "@fontsource-variable/archivo/wdth.css";
import "./design/tokens.css";
import "./design/base.css";
import "./design/components.css";
import "./app/shell.css";
import "./i18n";
import { QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { router } from "./app/router";
import { ToastProvider } from "./design";
import { queryClient } from "./lib/api";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>
  </StrictMode>,
);
