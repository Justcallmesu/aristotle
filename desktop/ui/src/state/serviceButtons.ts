import type { MessageKey, Params } from "../i18n";
import type { Status } from "../ipc";
import type { World } from "./world";

export interface ServiceButtons {
  start: boolean;
  stop: boolean;
  restart: boolean;
  /** The written reason beside the disabled buttons (design.md 4.4, 4.3 `dis_*`); null when none applies. */
  reason: { key: MessageKey; params?: Params } | null;
}

/**
 * P21, the one "can Start" rule for the tray menu, Status, Service and the needs-server state:
 * the unit is installed, the versions match, and the service word is `inactive` or `failed`.
 * (A running command is the caller's: it disables the button, it does not hide it.)
 */
export function canStart(status: Status | null, world: World): boolean {
  if (!status || !status.installed || world.mismatch) return false;
  return status.service === "inactive" || status.service === "failed";
}

/**
 * design.md 4.4 "Which buttons are on", same rule as the tray menu: only `active` allows Stop and
 * Restart (also when the server does not answer: recovery), Start follows `canStart`, every other
 * systemctl word turns all three off with `dis_other`. Not installed, a version mismatch or a
 * running command turn all off too.
 */
export function serviceButtons(status: Status | null, world: World, busy: boolean): ServiceButtons {
  const off = (reason: ServiceButtons["reason"]): ServiceButtons => ({ start: false, stop: false, restart: false, reason });
  if (!status) return off(null);
  if (!status.installed) return off({ key: "dis_notinst" });
  if (world.mismatch) return off({ key: "dis_mismatch" });
  if (busy) return off(null);
  const service = status.service;
  if (service === "active") return { start: false, stop: true, restart: true, reason: { key: "dis_running" } };
  if (canStart(status, world)) return { start: true, stop: false, restart: false, reason: { key: "dis_stopped" } };
  return off({ key: "dis_other", params: { word: service } });
}
