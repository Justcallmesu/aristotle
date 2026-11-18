import type { Device, SnapshotDto, Status, StatusResult } from "../../../ipc";

const result: StatusResult = {
  version: "1.2.3",
  addresses: [{ url: "https://192.168.1.20:8760", kind: "lan", iface: "wlan0" }],
  port: 8760,
  listeners: [
    { kind: "lan", iface: "wlan0", ip: "192.168.1.20", subnet: "192.168.1.0/24" },
    { kind: "tailscale", iface: "tailscale0", ip: "100.64.0.7", subnet: null },
    { kind: "lan", iface: "lo", ip: "127.0.0.1", subnet: null },
  ],
  hermes: { state: "reachable", reason: null },
};

const device = (id: string, name: string, minutesAgo: number): Device => ({
  id,
  name,
  created_at: "2026-11-10T09:00:00Z",
  last_seen_at: new Date(Date.now() - minutesAgo * 60_000).toISOString(),
});

export function snapshot(status: Partial<Status> = {}, rest: Partial<SnapshotDto> = {}): SnapshotDto {
  return {
    status: {
      kind: "answered",
      status: { service: "active", enabled: "enabled", installed: true, server: "running", result, ...status },
    },
    devices: [device("dev_a1b2c3d4e5f6", "pixel", 4), device("dev_0f9e8d7c6b5a", "tablet", 190)],
    terminals: [{ id: "term_1", device_id: "dev_a1b2c3d4e5f6", listener: "lan", since: "2026-11-18T10:00:00Z", pid: 4121 }],
    terminal_enabled: true,
    seq: 1,
    version: { kind: "match" },
    assessment: { state: "green", reason: "all-ok" },
    app_version: "1.2.3",
    ...rest,
  };
}

export const stopped = (rest: Partial<Status> = {}) => snapshot({ service: "inactive", server: "not-running", result: null, ...rest });
export const hermes = (state: string | null, reason: string | null = null) =>
  snapshot({ result: { ...result, hermes: state ? { state, reason } : null } });
export const mismatch = (status: Partial<Status> = {}) =>
  snapshot(status, { version: { kind: "mismatch", server: "1.2.2", desktop: "1.2.3" } });

export const LOG_LINES = [
  'Nov 18 20:31:02 cachyos aristotle-server[2412]: level=INFO msg="listening" addr=192.168.1.20:8760',
  'Nov 18 20:31:02 cachyos aristotle-server[2412]: level=INFO msg="listening" addr=100.64.0.7:8760',
  'Nov 18 20:31:09 cachyos aristotle-server[2412]: level=INFO msg="device paired" device=dev_a1b2c3d4e5f6',
  'Nov 18 20:32:15 cachyos aristotle-server[2412]: level=WARN msg="hermes socket slow" took=2.1s',
  'Nov 18 20:33:40 cachyos aristotle-server[2412]: level=ERROR msg="hermes request failed" err="connection refused"',
  'Nov 18 20:33:41 cachyos aristotle-server[2412]: level=INFO msg="retrying" attempt=1',
  "Nov 18 20:34:02 cachyos aristotle-server[2412]: level=INFO msg=\"a very long line that goes on and on so that the wrap switch shows what it does when a journal line is wider than the log box itself, which happens often\"",
  "Nov 18 20:35:00 cachyos aristotle-server[2412]: <script>alert(1)</script> is shown as text",
];
