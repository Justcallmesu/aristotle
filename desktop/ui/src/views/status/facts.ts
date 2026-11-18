import type { Translator } from "../../i18n";
import type { Device, SnapshotDto, Status } from "../../ipc";

export const DASH = "—";

/** The `status --json` answer, or null while nothing usable has arrived (loading, no CLI, failed poll). */
export function statusOf(snapshot: SnapshotDto | null): Status | null {
  return snapshot?.status.kind === "answered" ? snapshot.status.status : null;
}

/** `svc_*` word of the systemctl state; a word the catalog lacks shows raw (design.md 4.1). */
export function serviceWord(tr: Translator, status: Status | null): string {
  return status ? tr.word("svc_", status.service) : DASH;
}

/** "Starts at login": not installed wins over whatever `enabled` says (P13). */
export function enabledWord(tr: Translator, status: Status | null): string {
  if (!status) return DASH;
  if (!status.installed) return tr.t("en_not-found");
  return tr.word("en_", status.enabled);
}

const MONTHS_EN = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const MONTHS_ID = ["Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des"];

function hhmm(date: Date): string {
  return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
}

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/** design.md 4.7 time text: now, N min ago, today HH:MM, else `Mon D HH:MM` (EN) / `D Mon HH:MM` (ID). */
export function seenText(tr: Translator, iso: string, now: Date = new Date()): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return DASH;
  const minutes = Math.floor((now.getTime() - date.getTime()) / 60_000);
  if (minutes < 1) return tr.t("now");
  if (minutes < 60) return tr.t("ago_min", { n: minutes });
  if (sameDay(date, now)) return tr.t("today", { t: hhmm(date) });
  const month = (tr.locale === "id" ? MONTHS_ID : MONTHS_EN)[date.getMonth()];
  const day = date.getDate();
  return `${tr.locale === "id" ? `${day} ${month}` : `${month} ${day}`} ${hhmm(date)}`;
}

/** The device seen most recently, for the Phones summary. */
export function latestSeen(devices: Device[]): Device | null {
  let best: Device | null = null;
  let bestTime = -Infinity;
  for (const device of devices) {
    const time = new Date(device.last_seen_at).getTime();
    if (!Number.isNaN(time) && time > bestTime) {
      best = device;
      bestTime = time;
    }
  }
  return best ?? devices[0] ?? null;
}

/** `ip:port`, IPv6 bracketed; the port is absent on an older server. */
export function listenAddress(ip: string, port: number | null): string {
  if (port === null) return ip;
  return ip.includes(":") ? `[${ip}]:${port}` : `${ip}:${port}`;
}
