import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fakeTransport } from "../test/fakes";
import { COMMANDS } from "./commands";
import { createClient } from "./client";
import type { CommandMap, CommandName } from "./types";

function rustCommands(): string[] {
  const source = readFileSync(join(__dirname, "../../../app/src/command_list.rs"), "utf8");
  const list = source.slice(source.indexOf("COMMANDS"));
  return [...list.matchAll(/"([a-z_]+)"/g)].map((m) => m[1]);
}

describe("IPC client", () => {
  it("lists exactly the commands the Rust side registers", () => {
    expect([...COMMANDS].sort()).toEqual(rustCommands().sort());
  });

  it("types every command (a missing key here fails the type check)", () => {
    const keys: Record<CommandName, true> = {
      snapshot: true, start_intent: true, service_action: true, pair: true, devices_revoke: true,
      terminal_status: true, terminal_grant: true, terminal_ungrant: true, terminal_enable: true,
      terminal_disable: true, logs_start: true, logs_stop: true,
    };
    const typed: (keyof CommandMap)[] = Object.keys(keys) as CommandName[];
    expect(typed.sort()).toEqual([...COMMANDS].sort());
  });

  it("calls each wrapper with the Rust command name and argument names", async () => {
    const fake = fakeTransport(Object.fromEntries(COMMANDS.map((c) => [c, null])));
    const client = createClient(fake.transport);
    await client.snapshot(true);
    await client.startIntent();
    await client.serviceAction("stop");
    await client.pair("pixel");
    await client.pair();
    await client.devicesRevoke("d1");
    await client.terminalStatus();
    await client.terminalGrant("d1");
    await client.terminalUngrant("d1");
    await client.terminalEnable();
    await client.terminalDisable();
    await client.logsStart();
    await client.logsStop();
    expect(fake.calls).toEqual([
      { command: "snapshot", args: { fresh: true } },
      { command: "start_intent", args: {} },
      { command: "service_action", args: { action: "stop" } },
      { command: "pair", args: { name: "pixel" } },
      { command: "pair", args: { name: null } },
      { command: "devices_revoke", args: { id: "d1" } },
      { command: "terminal_status", args: {} },
      { command: "terminal_grant", args: { id: "d1" } },
      { command: "terminal_ungrant", args: { id: "d1" } },
      { command: "terminal_enable", args: {} },
      { command: "terminal_disable", args: {} },
      { command: "logs_start", args: {} },
      { command: "logs_stop", args: {} },
    ]);
  });

  it("passes event payloads through and stops listening", async () => {
    const fake = fakeTransport();
    const client = createClient(fake.transport);
    const seen: unknown[] = [];
    const off = await client.on("logs-lines", (p) => seen.push(p));
    fake.emit("logs-lines", { lines: ["a"] });
    off();
    fake.emit("logs-lines", { lines: ["b"] });
    expect(seen).toEqual([{ lines: ["a"] }]);
  });

  it("rejects with the typed error the backend sent", async () => {
    const client = createClient({
      invoke: () => Promise.reject({ kind: "version-mismatch", message: null, exit_code: null }),
      listen: async () => () => undefined,
    });
    await expect(client.serviceAction("stop")).rejects.toMatchObject({ kind: "version-mismatch" });
  });
});
