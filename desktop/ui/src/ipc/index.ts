// The frontend's only door to the Rust side (P4): views import from here, never from @tauri-apps.
export { COMMANDS } from "./commands";
export { createClient, isIpcError } from "./client";
export type { IpcClient, Transport, Unlisten } from "./client";
export { IpcProvider, useIpc } from "./context";
export { ipc } from "./tauri";
export * from "./types";
