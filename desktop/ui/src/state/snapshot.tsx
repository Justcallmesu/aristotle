import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { useIpc, type SnapshotDto } from "../ipc";
import { deriveWorld, type World } from "./world";

interface SnapshotState {
  snapshot: SnapshotDto | null;
  world: World;
}

const SnapshotContext = createContext<SnapshotState>({ snapshot: null, world: deriveWorld(null) });

/** The latest poll: one read at start, then the backend's `snapshot` event every 5 s. */
export function SnapshotProvider({ children }: { children: ReactNode }) {
  const client = useIpc();
  const [snapshot, setSnapshot] = useState<SnapshotDto | null>(null);

  useEffect(() => {
    let alive = true;
    let unlisten: (() => void) | undefined;
    client
      .on("snapshot", (next) => alive && setSnapshot(next))
      .then((off) => (alive ? (unlisten = off) : off()));
    client
      .snapshot()
      .then((first) => alive && setSnapshot((current) => current ?? first))
      .catch(() => undefined);
    return () => {
      alive = false;
      unlisten?.();
    };
  }, [client]);

  const value = useMemo(() => ({ snapshot, world: deriveWorld(snapshot) }), [snapshot]);
  return <SnapshotContext.Provider value={value}>{children}</SnapshotContext.Provider>;
}

export function useSnapshot(): SnapshotState {
  return useContext(SnapshotContext);
}

/** Actions are off while the server and the app disagree (P5); views write `dis_mismatch` beside them. */
export function useActionGuard(): { disabled: boolean; reasonKey: "dis_mismatch" | null } {
  const { world } = useSnapshot();
  const disabled = world.mismatch !== null;
  return { disabled, reasonKey: disabled ? "dis_mismatch" : null };
}
