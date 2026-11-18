import type { CommandName } from "./types";

/** Must equal `COMMANDS` in desktop/app/src/command_list.rs (a test compares them). */
export const COMMANDS = [
  "snapshot",
  "start_intent",
  "service_action",
  "pair",
  "devices_revoke",
  "terminal_status",
  "terminal_grant",
  "terminal_ungrant",
  "terminal_enable",
  "terminal_disable",
  "logs_start",
  "logs_stop",
] as const satisfies readonly CommandName[];

// Compile-time check that no command in CommandMap is missing from the list above.
type Missing = Exclude<CommandName, (typeof COMMANDS)[number]>;
export const everyCommandListed: Missing extends never ? true : never = true;
