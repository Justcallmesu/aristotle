import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { createClient, type Transport } from "./client";

const tauriTransport: Transport = {
  invoke: (command, args) => invoke(command, args),
  listen: (event, handler) => listen(event, (e) => handler(e.payload)),
};

export const ipc = createClient(tauriTransport);
