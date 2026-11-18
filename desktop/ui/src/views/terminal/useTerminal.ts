import { useCallback, useEffect, useRef, useState } from "react";
import { useIpc, type Device, type GrantedDevice, type OpenTerminal, type TerminalStatus } from "../../ipc";
import { useSnapshot } from "../../state/snapshot";

/** The fields a view may rely on; the server's own shape is read defensively (§22). */
export interface TerminalData {
  enabled: boolean;
  open: OpenTerminal[];
  warning: string | null;
  problem: string | null;
}

function normalise(status: TerminalStatus): TerminalData {
  return {
    enabled: status.enabled === true,
    open: status.open ?? [],
    warning: status.warning || null,
    problem: status.problem || null,
  };
}

export interface TerminalState {
  /** Last good `terminal status`; null before the first read, or when every read failed. */
  data: TerminalData | null;
  /** `data.open` is replaced by the live snapshot list when the poll has one. */
  open: OpenTerminal[];
  /** Who holds a grant, from the poll (ids) and the device list (names); null when the poll could not read it. */
  granted: GrantedDevice[] | null;
  devices: Device[];
  loading: boolean;
  /** Takes the result of a change command (enable, disable, grant, ungrant). */
  apply: (status: TerminalStatus) => void;
  /** Re-reads `terminal status` (Check again). */
  reread: () => Promise<void>;
  /** The terminals open right now, read fresh (the poll is up to 5 s old); the poll's list if the read fails. */
  freshOpen: () => Promise<OpenTerminal[]>;
}

/**
 * `terminal status` is read when the server is up, when the paired phones change, when the
 * poll's switch disagrees with the last read, and after every action. There is no timer here;
 * the snapshot event is the only clock. Only the newest request may write (a slow read must not
 * undo a change that finished after it started).
 */
export function useTerminalData(): TerminalState {
  const client = useIpc();
  const { snapshot, world } = useSnapshot();
  const [data, setData] = useState<TerminalData | null>(null);
  const [settled, setSettled] = useState(false);
  const latest = useRef(0);

  const devices = snapshot?.devices ?? [];
  const devicesKey = devices.map((d) => d.id).join("\n");
  const polledEnabled = snapshot?.terminal_enabled ?? null;

  const apply = useCallback((status: TerminalStatus) => {
    latest.current += 1;
    setData(normalise(status));
    setSettled(true);
  }, []);

  const reread = useCallback(async () => {
    const ticket = (latest.current += 1);
    try {
      const status = await client.terminalStatus();
      if (ticket === latest.current) setData(normalise(status));
    } catch {
      // A failed read keeps the last good data (design.md 4.2).
    } finally {
      if (ticket === latest.current) setSettled(true);
    }
  }, [client]);

  const polledOpen = snapshot?.terminals ?? null;
  const freshOpen = useCallback(async () => {
    try {
      return (await client.terminalStatus()).open ?? [];
    } catch {
      return polledOpen ?? [];
    }
  }, [client, polledOpen]);
  useEffect(() => {
    if (!world.running) return;
    void reread();
  }, [world.running, devicesKey, polledEnabled, reread]);

  return {
    data,
    open: snapshot?.terminals ?? data?.open ?? [],
    granted: grantedFrom(snapshot?.terminal_granted, snapshot?.terminal_enabled ?? null, devices),
    devices,
    loading: world.row === 0 || (world.running && !settled),
    apply,
    reread,
    freshOpen,
  };
}

/** Ids from the poll with the names the device list knows; `name` is empty for a device that is gone. */
function grantedFrom(ids: string[] | undefined, enabled: boolean | null, devices: Device[]): GrantedDevice[] | null {
  if (!ids || enabled === null) return null;
  return ids.map((id) => ({ id, name: devices.find((d) => d.id === id)?.name ?? "" }));
}
