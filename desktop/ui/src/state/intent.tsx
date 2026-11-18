import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { useIpc, type StartIntent } from "../ipc";
import { navigate } from "./route";

export interface PendingIntent {
  /** Distinguishes two hand-offs of the same kind. */
  id: number;
  intent: StartIntent;
}

interface IntentState {
  pending: PendingIntent | null;
  /** The view that handled the intent (issued the code, opened the dialog) calls this once. */
  acknowledge: (id: number) => void;
}

const IntentContext = createContext<IntentState>({ pending: null, acknowledge: () => undefined });

function viewFor(intent: StartIntent) {
  switch (intent.kind) {
    case "pair":
      return "pair";
    case "confirm-stop":
      return "service";
    case "status":
      return "status";
  }
}

/**
 * The window's start intent (P14): read once at start (the event fires before any listener exists),
 * and again on every single-instance hand-off. It opens the right view; the view then acts on it.
 */
export function IntentProvider({ children }: { children: ReactNode }) {
  const client = useIpc();
  const [pending, setPending] = useState<PendingIntent | null>(null);

  useEffect(() => {
    let alive = true;
    let next = 0;
    let unlisten: (() => void) | undefined;
    const receive = (intent: StartIntent) => {
      if (!alive) return;
      navigate(viewFor(intent));
      setPending({ id: ++next, intent });
    };
    // The listener first: a hand-off between the read and the listener would be lost otherwise.
    void (async () => {
      try {
        const off = await client.on("start-intent", receive);
        if (alive) unlisten = off;
        else off();
        if (alive) receive(await client.startIntent());
      } catch {
        // No intent to act on: the window opens on Status.
      }
    })();
    return () => {
      alive = false;
      unlisten?.();
    };
  }, [client]);

  const acknowledge = useCallback(
    (id: number) => setPending((p) => (p && p.id === id ? null : p)),
    [],
  );
  const value = useMemo(() => ({ pending, acknowledge }), [pending, acknowledge]);
  return <IntentContext.Provider value={value}>{children}</IntentContext.Provider>;
}

export function useStartIntent(): IntentState {
  return useContext(IntentContext);
}
