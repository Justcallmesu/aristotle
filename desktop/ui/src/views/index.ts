import type { ComponentType } from "react";
import type { View } from "../state/route";
import { DevicesView } from "./devices/DevicesView";
import { LogsView } from "./logs/LogsView";
import { PairView } from "./pair/PairView";
import { ServiceView } from "./service/ServiceView";
import { StatusView } from "./status/StatusView";
import { TerminalView } from "./terminal/TerminalView";

/** One entry per route. DS4-DS6 replace the component inside their own folder only. */
export const viewComponents: Record<View, ComponentType> = {
  status: StatusView,
  service: ServiceView,
  logs: LogsView,
  pair: PairView,
  devices: DevicesView,
  terminal: TerminalView,
};
