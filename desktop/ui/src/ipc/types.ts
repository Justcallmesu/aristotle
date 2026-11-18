// Shapes that cross the Tauri boundary. They mirror docs/protocol.md section 22 and the DTOs in
// desktop/app/src/dto.rs; Rust `Option` fields arrive as `null`.

export type StartIntent = { kind: "status" } | { kind: "pair" } | { kind: "confirm-stop" };

export type ServiceAction = "start" | "stop" | "restart";

export interface Address {
  url: string;
  kind: string;
  iface: string;
}

export interface Listener {
  kind: string;
  iface: string;
  ip: string;
  subnet: string | null;
}

export interface Hermes {
  state: string;
  reason: string | null;
}

export interface StatusResult {
  version: string;
  addresses: Address[];
  port: number | null;
  listeners: Listener[] | null;
  hermes: Hermes | null;
}

export interface Status {
  /** systemctl `is-active` word, or "unknown". */
  service: string;
  enabled: string;
  installed: boolean;
  /** "running" | "not-running" | "no-answer"; a newer server may add words. */
  server: string;
  result: StatusResult | null;
}

export interface Device {
  id: string;
  name: string;
  created_at: string;
  last_seen_at: string;
}

export interface GrantedDevice {
  id: string;
  name: string;
}

export interface OpenTerminal {
  id: string;
  device_id: string;
  listener: string;
  since: string;
  pid: number;
}

export interface TerminalStatus {
  enabled: boolean;
  granted: GrantedDevice[];
  open: OpenTerminal[];
  changed: boolean;
  warning: string | null;
  problem: string | null;
  device: Device | null;
}

export interface PairAddress extends Address {
  default_route: boolean;
}

export interface Pair {
  code: string;
  expires_at: string;
  fingerprint: string;
  addresses: PairAddress[];
  qr_payload: string;
  /** QR matrix of qr_payload: equal-length "0"/"1" rows, no quiet zone (P19). Absent when not encodable. */
  qr_modules?: string[];
  qr_error: string | null;
}

export interface ActionResult {
  ok: boolean;
  service: string;
}

export type StatusDto =
  | { kind: "answered"; status: Status }
  | { kind: "cli-missing" }
  | { kind: "failed" };

export type VersionDto =
  | { kind: "unchecked" }
  | { kind: "skipped" }
  | { kind: "match" }
  | { kind: "mismatch"; server: string; desktop: string };

export interface SnapshotDto {
  status: StatusDto;
  devices: Device[] | null;
  terminals: OpenTerminal[] | null;
  /** The terminal switch from the same poll; null when that read failed. */
  terminal_enabled: boolean | null;
  /** Grows with every poll; an older snapshot than the one shown is dropped. */
  seq: number;
  version: VersionDto;
  assessment: { state: "green" | "amber" | "red"; reason: string };
  /** This build's version; null for dev builds (P5). */
  app_version: string | null;
}

/** What a failed command rejects with: a core `CliError::kind()` code or an app code. */
export interface IpcError {
  kind: string;
  message: string | null;
  exit_code: number | null;
}

export interface LogLines {
  lines: string[];
}

export interface CommandMap {
  snapshot: { args: { fresh?: boolean }; result: SnapshotDto };
  start_intent: { args: Record<string, never>; result: StartIntent };
  service_action: { args: { action: ServiceAction }; result: ActionResult };
  pair: { args: { name?: string | null }; result: Pair };
  devices_revoke: { args: { id: string }; result: Device };
  terminal_status: { args: Record<string, never>; result: TerminalStatus };
  terminal_grant: { args: { id: string }; result: TerminalStatus };
  terminal_ungrant: { args: { id: string }; result: TerminalStatus };
  terminal_enable: { args: Record<string, never>; result: TerminalStatus };
  terminal_disable: { args: Record<string, never>; result: TerminalStatus };
  logs_start: { args: Record<string, never>; result: null };
  logs_stop: { args: Record<string, never>; result: null };
}

export type CommandName = keyof CommandMap;

export interface EventMap {
  snapshot: SnapshotDto;
  "start-intent": StartIntent;
  "logs-lines": LogLines;
  "logs-ended": null;
}

export type EventName = keyof EventMap;
