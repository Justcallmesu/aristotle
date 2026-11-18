import type { Pair } from "../../ipc";

/** Quiet zone in modules (design.md 3.7). */
export const QUIET_ZONE = 4;

/** `qr_modules` (spec P19): returns null unless it is a matrix of equal-length `0`/`1` rows. */
export function modulesOf(pair: Pair): string[] | null {
  const rows = pair.qr_modules;
  if (!Array.isArray(rows) || rows.length === 0) return null;
  const width = rows[0]?.length ?? 0;
  const valid = width > 0 && rows.every((row) => typeof row === "string" && row.length === width && /^[01]+$/.test(row));
  return valid ? rows : null;
}
