import type { ReactNode } from "react";

export function Tag({ tone = "neutral", children }: { tone?: "neutral" | "ok" | "warn" | "err"; children: ReactNode }) {
  return <span className={`tag tag-${tone}`}>{children}</span>;
}
