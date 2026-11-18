import type { MessageKey } from "../../i18n";
import type { Status } from "../../ipc";
import type { World } from "../../state/world";

export interface ServiceButtons {
  start: boolean;
  stop: boolean;
  restart: boolean;
  /** The written reason beside the disabled buttons (design.md 4.4, 4.3 `dis_*`); null when none applies. */
  reason: MessageKey | null;
}

/**
 * design.md 4.4 "Which buttons are on", same rule as the tray menu: only `active` allows Stop and
 * Restart (also when the server does not answer: recovery), only `inactive`/`failed` allows Start,
 * every other systemctl word turns all three off. Not installed, a version mismatch or a running
 * command turn all off too.
 */
export function serviceButtons(status: Status | null, world: World, busy: boolean): ServiceButtons {
  const off = (reason: MessageKey | null): ServiceButtons => ({ start: false, stop: false, restart: false, reason });
  if (!status) return off(null);
  if (!status.installed) return off("dis_notinst");
  if (world.mismatch) return off("dis_mismatch");
  const service = status.service;
  if (busy) return off(null);
  if (service === "active") return { start: false, stop: true, restart: true, reason: "dis_running" };
  if (service === "inactive" || service === "failed") return { start: true, stop: false, restart: false, reason: "dis_stopped" };
  return off(null);
}
