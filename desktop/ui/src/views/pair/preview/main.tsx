// Screenshot harness for DS5 (dev only, never shipped: vite build only bundles /index.html).
// ?state=<name>&theme=light|dark&locale=en|id renders the real Shell + view over a fake IPC transport.
import { createRoot } from "react-dom/client";
import { Shell } from "../../../components/Shell";
import { I18nProvider } from "../../../i18n";
import { createClient, IpcProvider, type Device, type SnapshotDto, type Transport } from "../../../ipc";
import { IntentProvider } from "../../../state/intent";
import { SnapshotProvider } from "../../../state/snapshot";
import "../../../styles/index.css";
import { viewComponents } from "../..";

const params = new URLSearchParams(location.search);
const state = params.get("state") ?? "pair-before";
const theme = params.get("theme") ?? "light";
const locale = params.get("locale") === "id" ? "id" : "en";
document.documentElement.dataset.theme = theme;

const NOW = Date.now();
const at = (ms: number) => new Date(NOW + ms).toISOString();
const device = (id: string, name: string, paired: number, seen: number): Device => ({
  id,
  name,
  created_at: at(paired),
  last_seen_at: at(seen),
});
const DEVICES = [
  device("d-5f3a91c2", "pixel", -20 * 86400e3, -3 * 60e3),
  device("d-0b77e4d8", "tablet", -61 * 86400e3, -30 * 3600e3),
  device("d-c1904a6e", "old phone", -90 * 86400e3, -0.2 * 60e3),
];

// A deterministic pseudo-QR (29 modules, finder squares): a preview only, not decodable.
const MODULES = (() => {
  const n = 29;
  const rows: string[] = [];
  for (let y = 0; y < n; y++) {
    let row = "";
    for (let x = 0; x < n; x++) {
      const finder = (cx: number, cy: number) => {
        const dx = x - cx;
        const dy = y - cy;
        if (dx < 0 || dx > 6 || dy < 0 || dy > 6) return null;
        return dx === 0 || dx === 6 || dy === 0 || dy === 6 || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4) ? "1" : "0";
      };
      const f = finder(0, 0) ?? finder(n - 7, 0) ?? finder(0, n - 7);
      row += f ?? ((x * 7 + y * 13 + x * y) % 3 === 0 ? "1" : "0");
    }
    rows.push(row);
  }
  return rows;
})();

const pairReply = (patch: Record<string, unknown> = {}) => ({
  code: "K7Q2M9XD",
  expires_at: at(4 * 60e3 + 12e3),
  fingerprint: "7E54 D87B 0C1F 9A42 6B3E 11D0 8F5C 2A97 4E06 B1C8 D3F2 5A69 0E7B C4D1 BCA7 EAA7",
  addresses: [
    { url: "https://192.168.1.5:8760", kind: "lan", iface: "wlan0", default_route: true },
    { url: "https://100.74.61.96:8760", kind: "tailscale", iface: "tailscale0", default_route: false },
  ],
  qr_payload: "aristotle://pair?v=1&c=K7Q2M9XD",
  qr_error: null,
  qr_modules: MODULES,
  ...patch,
});

function snapshot(patch: Partial<SnapshotDto> = {}, stopped = false): SnapshotDto {
  return {
    status: {
      kind: "answered",
      status: stopped
        ? { service: "inactive", enabled: "enabled", installed: true, server: "not-running", result: null }
        : {
            service: "active",
            enabled: "enabled",
            installed: true,
            server: "running",
            result: { version: "1.2.3", addresses: [], port: 8760, listeners: null, hermes: { state: "reachable", reason: null } },
          },
    },
    devices: DEVICES,
    terminals: [],
    terminal_enabled: true,
    terminal_granted: ["d-5f3a91c2"],
    seq: 1,
    version: { kind: "match" },
    assessment: { state: "green", reason: "all-ok" },
    app_version: "1.2.3",
    ...patch,
  };
}

const mismatch = { version: { kind: "mismatch", server: "1.0.0", desktop: "1.2.3" } } as const;
const SHOW = /Show pairing code|Tampilkan/;
const REVOKE = /Revoke…|Cabut…/;
interface Scenario {
  view: "pair" | "devices";
  snap: SnapshotDto;
  pair?: () => Promise<unknown>;
  click?: RegExp;
  confirm?: boolean;
}
const never = () => new Promise<never>(() => undefined);
const scenarios: Record<string, Scenario> = {
  "pair-before": { view: "pair", snap: snapshot() },
  "pair-name-error": { view: "pair", snap: snapshot() },
  "pair-busy": { view: "pair", snap: snapshot(), pair: never, click: SHOW },
  "pair-code": { view: "pair", snap: snapshot(), pair: async () => pairReply(), click: SHOW },
  "pair-expired": { view: "pair", snap: snapshot(), pair: async () => pairReply({ expires_at: at(-60e3) }), click: SHOW },
  "pair-no-listener": {
    view: "pair",
    snap: snapshot(),
    click: SHOW,
    pair: async () =>
      pairReply({ qr_payload: "", qr_modules: undefined, addresses: [], qr_error: "no listener is up: LAN and Tailscale are both down" }),
  },
  "pair-failed": {
    view: "pair",
    snap: snapshot(),
    pair: () => Promise.reject({ kind: "cli", message: "pairing store is locked", exit_code: 1 }),
    click: SHOW,
  },
  "pair-needs-server": { view: "pair", snap: snapshot({}, true) },
  "pair-mismatch": { view: "pair", snap: snapshot(mismatch) },
  "dev-list": { view: "devices", snap: snapshot() },
  "dev-empty": { view: "devices", snap: snapshot({ devices: [] }) },
  "dev-needs-server": { view: "devices", snap: snapshot({}, true) },
  "dev-mismatch": { view: "devices", snap: snapshot(mismatch) },
  "dev-revoke": { view: "devices", snap: snapshot(), click: REVOKE },
  "dev-failed": { view: "devices", snap: snapshot(), click: REVOKE, confirm: true },
};
const sc = scenarios[state] ?? scenarios["pair-before"];

const transport: Transport = {
  invoke: async (command) => {
    switch (command) {
      case "snapshot":
        return sc.snap;
      case "start_intent":
        return { kind: "status" };
      case "pair":
        return sc.pair ? sc.pair() : pairReply();
      case "devices_revoke":
        return Promise.reject({ kind: "cli", message: "no such device", exit_code: 1 });
      default:
        throw { kind: "internal", message: null, exit_code: null };
    }
  },
  listen: async () => () => undefined,
};

location.hash = `/${sc.view}`;
const View = viewComponents[sc.view];
createRoot(document.getElementById("root")!).render(
  <I18nProvider locale={locale}>
    <IpcProvider client={createClient(transport)}>
      <SnapshotProvider>
        <IntentProvider>
          <Shell>
            <View />
          </Shell>
        </IntentProvider>
      </SnapshotProvider>
    </IpcProvider>
  </I18nProvider>,
);

// Drive the view into the state: type a bad name, click a button, confirm Revoke.
function drive(): boolean {
  const buttons = () => Array.from(document.querySelectorAll("button"));
  if (state === "pair-name-error") {
    const input = document.querySelector<HTMLInputElement>("#pair-name");
    if (!input) return false;
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(input, "-x");
    input.dispatchEvent(new Event("input", { bubbles: true }));
    return true;
  }
  if (!sc.click) return true;
  const btn = buttons().find((b) => sc.click!.test(b.textContent ?? "") && !b.closest("[role=alertdialog]"));
  if (!btn) return false;
  btn.click();
  if (sc.confirm) {
    setTimeout(() => buttons().find((b) => b.closest("[role=alertdialog]") && /^(Revoke|Cabut)$/.test(b.textContent ?? ""))?.click(), 150);
  }
  return true;
}
let tries = 0;
const timer = setInterval(() => {
  if (drive() || ++tries > 40) {
    clearInterval(timer);
    setTimeout(() => (document.documentElement.dataset.ready = "1"), 400);
  }
}, 100);
