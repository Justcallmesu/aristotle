/** Local `HH:MM`, 24 h; `—` when the server sent something that is not a time. */
export function clock(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  const two = (n: number) => String(n).padStart(2, "0");
  return `${two(date.getHours())}:${two(date.getMinutes())}`;
}

/** Whole minutes since `iso`, never negative; null when it is not a time. */
export function minutesSince(iso: string, now: number): number | null {
  const then = new Date(iso).getTime();
  return Number.isNaN(then) ? null : Math.max(0, Math.floor((now - then) / 60_000));
}
