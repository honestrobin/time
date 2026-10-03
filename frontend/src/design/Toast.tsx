// SPDX-License-Identifier: AGPL-3.0-only
import * as RToast from "@radix-ui/react-toast";
import { createContext, useCallback, useContext, useState, type ReactNode } from "react";

interface ToastItem {
  id: number;
  message: ReactNode;
  kind: "ok" | "error";
}

const ToastContext = createContext<(message: ReactNode, kind?: "ok" | "error") => void>(() => {});

let nextId = 1;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const push = useCallback((message: ReactNode, kind: "ok" | "error" = "ok") => {
    setItems((list) => [...list.slice(-3), { id: nextId++, message, kind }]);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      <RToast.Provider duration={4000}>
        {children}
        {items.map((item) => (
          <RToast.Root
            key={item.id}
            className={item.kind === "error" ? "toast toast-error" : "toast"}
            onOpenChange={(open) => !open && setItems((list) => list.filter((i) => i.id !== item.id))}
          >
            <RToast.Description>{item.message}</RToast.Description>
          </RToast.Root>
        ))}
        <RToast.Viewport className="toast-viewport" />
      </RToast.Provider>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);
