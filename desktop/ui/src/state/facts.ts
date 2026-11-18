import { DASH } from "../format/dash";
import type { Translator } from "../i18n";
import type { Device, SnapshotDto, Status } from "../ipc";

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
