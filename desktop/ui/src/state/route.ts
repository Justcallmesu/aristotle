import { useCallback, useSyncExternalStore } from "react";

export const VIEWS = ["status", "service", "logs", "pair", "devices", "terminal"] as const;
export type View = (typeof VIEWS)[number];

export function parseRoute(hash: string): View {
  const name = hash.replace(/^#\/?/, "");
  return (VIEWS as readonly string[]).includes(name) ? (name as View) : "status";
}

function subscribe(onChange: () => void): () => void {
  window.addEventListener("hashchange", onChange);
  return () => window.removeEventListener("hashchange", onChange);
}

export function navigate(view: View): void {
  window.location.hash = `/${view}`;
}

/** The open view, kept in the URL hash so Tauri's asset protocol needs no server routing. */
export function useRoute(): [View, (view: View) => void] {
  const hash = useSyncExternalStore(subscribe, () => window.location.hash);
  return [parseRoute(hash), useCallback(navigate, [])];
}
