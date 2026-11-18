import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { I18nProvider, type Locale } from "../i18n";
import { createClient, IpcProvider, type IpcClient, type SnapshotDto, type Transport } from "../ipc";
import { SnapshotProvider } from "../state/snapshot";

export function snapshotOf(overrides: Partial<SnapshotDto> = {}): SnapshotDto {
  return {
    status: {
      kind: "answered",
      status: {
        service: "active",
        enabled: "enabled",
        installed: true,
        server: "running",
        result: {
          version: "1.2.3",
          addresses: [],
          port: 8760,
          listeners: null,
          hermes: { state: "reachable", reason: null },
        },
      },
    },
    devices: [],
    terminals: [],
    terminal_enabled: true,
    terminal_granted: [],
    seq: 1,
    version: { kind: "match" },
    assessment: { state: "green", reason: "all-ok" },
    app_version: "1.2.3",
    ...overrides,
  };
}

type Handler = (payload: unknown) => void;
/** A reply that is computed per call (to hold, delay or fail it); a plain value is returned as is. */
export type ReplyFn = (args?: Record<string, unknown>) => unknown;

/**
 * A transport that answers from a map and lets a test push events. `setReply(command, value)` changes
 * the answer from then on; a function value is called with the args and may return a promise.
 */
export function fakeTransport(initial: Record<string, unknown> = {}) {
  const replies: Record<string, unknown> = { ...initial };
  const calls: { command: string; args?: Record<string, unknown> }[] = [];
  const handlers = new Map<string, Set<Handler>>();
  const transport: Transport = {
    invoke: async (command, args) => {
      calls.push({ command, args });
      if (!(command in replies)) throw { kind: "internal", message: null, exit_code: null };
      const reply = replies[command];
      return typeof reply === "function" ? (reply as ReplyFn)(args) : reply;
    },
    listen: async (event, handler) => {
      const set = handlers.get(event) ?? new Set();
      set.add(handler);
      handlers.set(event, set);
      return () => set.delete(handler);
    },
  };
  return {
    transport,
    calls,
    setReply: (command: string, value: unknown) => {
      replies[command] = value;
    },
    emit: (event: string, payload: unknown) => handlers.get(event)?.forEach((h) => h(payload)),
  };
}

export function renderWithApp(
  ui: ReactElement,
  options: { client?: IpcClient; locale?: Locale } = {},
) {
  const client = options.client ?? createClient(fakeTransport({ snapshot: snapshotOf(), start_intent: { kind: "status" }, terminal_status: { enabled: false } }).transport);
  return render(
    <I18nProvider locale={options.locale ?? "en"}>
      <IpcProvider client={client}>
        <SnapshotProvider>{ui}</SnapshotProvider>
      </IpcProvider>
    </I18nProvider>,
  );
}
