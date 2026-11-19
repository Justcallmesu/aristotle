import { useCallback, useEffect, useRef, useState } from "react";
import { useIpc, type Device, type GrantedDevice, type OpenTerminal, type TerminalStatus } from "../../ipc";
import { useSnapshot } from "../../state/snapshot";

/** The fields a view may rely on; the server's own shape is read defensively (§22). */
export interface TerminalData {
  enabled: boolean;
  granted: GrantedDevice[];
  open: OpenTerminal[];
  warning: string | null;
  problem: string | null;
}

function normalise(status: TerminalStatus): TerminalData {
  return {
    enabled: status.enabled === true,
    granted: status.granted ?? [],
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
  /** Who holds a grant: the poll's ids, else the view's own last read; null when neither is known. */
  granted: GrantedDevice[] | null;
  devices: Device[];
  /** False when the poll could not read `devices list`: a missing name is then unknown, not unpaired. */
  devicesKnown: boolean;
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
    granted: grantedFrom(snapshot?.terminal_granted ?? null, data?.granted ?? null, devices),
    devices,
    devicesKnown: snapshot?.devices != null,
    loading: world.row === 0 || (world.running && !settled),
    apply,
    reread,
    freshOpen,
  };
}

/**
 * The poll's ids are the freshest; when it could not read them (null) the view's own `terminal status`
 * read stands in. Names: the server's own, else the device list, else empty.
 */
function grantedFrom(ids: string[] | null, own: GrantedDevice[] | null, devices: Device[]): GrantedDevice[] | null {
  if (ids === null) return own;
  return ids.map((id) => ({
    id,
    name: own?.find((g) => g.id === id)?.name || devices.find((d) => d.id === id)?.name || "",
  }));
}
