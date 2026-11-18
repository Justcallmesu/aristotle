import { COMMANDS } from "./commands";
import type {
  CommandMap,
  CommandName,
  EventMap,
  EventName,
  IpcError,
  ServiceAction,
} from "./types";

/** The only place that knows about Tauri. Tests pass a fake. */
export interface Transport {
  invoke(command: string, args?: Record<string, unknown>): Promise<unknown>;
  listen(event: string, handler: (payload: unknown) => void): Promise<() => void>;
}

export type Unlisten = () => void;

export function isIpcError(value: unknown): value is IpcError {
  return typeof value === "object" && value !== null && typeof (value as IpcError).kind === "string";
}

export function createClient(transport: Transport) {
  function call<C extends CommandName>(
    command: C,
    args: CommandMap[C]["args"] = {} as CommandMap[C]["args"],
  ): Promise<CommandMap[C]["result"]> {
    return transport.invoke(command, args) as Promise<CommandMap[C]["result"]>;
  }

  return {
    commands: COMMANDS,
    snapshot: (fresh = false) => call("snapshot", { fresh }),
    startIntent: () => call("start_intent"),
    serviceAction: (action: ServiceAction) => call("service_action", { action }),
    pair: (name?: string | null) => call("pair", { name: name ?? null }),
    devicesRevoke: (id: string) => call("devices_revoke", { id }),
    terminalStatus: () => call("terminal_status"),
    terminalGrant: (id: string) => call("terminal_grant", { id }),
    terminalUngrant: (id: string) => call("terminal_ungrant", { id }),
    terminalEnable: () => call("terminal_enable"),
    terminalDisable: () => call("terminal_disable"),
    logsStart: () => call("logs_start"),
    logsStop: () => call("logs_stop"),
    on<E extends EventName>(event: E, handler: (payload: EventMap[E]) => void): Promise<Unlisten> {
      return transport.listen(event, (payload) => handler(payload as EventMap[E]));
    },
  };
}

export type IpcClient = ReturnType<typeof createClient>;
