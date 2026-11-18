import { useEffect, useState } from "react";

/** Seconds until `expiresAt` (ms epoch), re-read every second; 0 at and after expiry. */
export function useCountdown(expiresAt: number | null): number {
  const left = (at: number) => Math.max(0, Math.ceil((at - Date.now()) / 1000));
  const [seconds, setSeconds] = useState(() => (expiresAt === null ? 0 : left(expiresAt)));
  useEffect(() => {
    if (expiresAt === null) return;
    setSeconds(left(expiresAt));
    const timer = window.setInterval(() => {
      const next = left(expiresAt);
      setSeconds(next);
      if (next === 0) window.clearInterval(timer);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [expiresAt]);
  return seconds;
}

export function mmss(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}
