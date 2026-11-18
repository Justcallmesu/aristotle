import { createContext, useContext, type ReactNode } from "react";
import type { IpcClient } from "./client";
import { ipc } from "./tauri";

const IpcContext = createContext<IpcClient>(ipc);

/** Tests and stories give the tree a fake client; the app uses the Tauri one. */
export function IpcProvider({ client, children }: { client: IpcClient; children: ReactNode }) {
  return <IpcContext.Provider value={client}>{children}</IpcContext.Provider>;
}

export function useIpc(): IpcClient {
  return useContext(IpcContext);
}
