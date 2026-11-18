// Dev-only state gallery for the screenshots (not part of the app build): `?state=<name>`,
// optional `&lang=id`. Fake IPC only, so it never talks to a real server.
import { createRoot } from "react-dom/client";
import { Shell } from "../../components/Shell";
import { I18nProvider } from "../../i18n";
import { createClient, IpcProvider, type Device, type SnapshotDto, type TerminalStatus } from "../../ipc";
import { SnapshotProvider } from "../../state/snapshot";
import "../../styles/index.css";
import { TerminalView } from "./TerminalView";

const phone = (id: string, name: string): Device => ({ id, name, created_at: "2026-11-01T08:00:00Z", last_seen_at: "2026-11-18T08:00:00Z" });
const devices = [phone("a1b2c3", "pixel"), phone("d4e5f6", "tablet")];
const since = new Date(Date.now() - 12 * 60_000).toISOString();
const open = [{ id: "term-9", device_id: "a1b2c3", listener: "tailscale", since, pid: 4242 }];

const terminal = (o: Partial<TerminalStatus> = {}): TerminalStatus => ({
  enabled: true, granted: [{ id: "a1b2c3", name: "pixel" }], open, changed: false, warning: null, problem: null, device: null, ...o,
});

function snapshot(o: Partial<SnapshotDto> = {}, server = "running", installed = true): SnapshotDto {
  return {
    status: { kind: "answered", status: { service: server === "running" ? "active" : "inactive", enabled: "enabled", installed, server,
      result: server === "running" ? { version: "1.2.3", addresses: [], port: 8760, listeners: null, hermes: { state: "reachable", reason: null } } : null } },
    devices, terminals: open, terminal_enabled: true, seq: 1, version: { kind: "match" },
    assessment: { state: "green", reason: "all-ok" }, app_version: "1.2.3", ...o,
  };
}

interface Scenario { snap: SnapshotDto; term?: TerminalStatus; steps?: string[]; fail?: boolean; never?: boolean }
const offSnap = snapshot({ terminal_enabled: false, terminals: [] });
const scenarios: Record<string, Scenario> = {
  loading: { snap: snapshot(), never: true },
  "needs-server": { snap: snapshot({}, "not-running") },
  "not-installed": { snap: snapshot({}, "not-running", false) },
  off: { snap: offSnap, term: terminal({ enabled: false, open: [] }) },
  on: { snap: snapshot(), term: terminal() },
  "on-empty": { snap: snapshot({ terminals: [] }), term: terminal({ granted: [], open: [] }) },
  warning: { snap: snapshot(), term: terminal({ warning: "terminal.json mode is 0644, expected 0600" }) },
  problem: { snap: offSnap, term: terminal({ enabled: false, granted: [], open: [], problem: "invalid character '}' at line 3" }) },
  mismatch: { snap: snapshot({ version: { kind: "mismatch", server: "1.0.0", desktop: "1.2.3" } }), term: terminal() },
  failed: { snap: offSnap, term: terminal({ enabled: false, open: [] }), steps: ["Turn on…", "Turn on remote terminal"], fail: true },
  "dialog-enable": { snap: offSnap, term: terminal({ enabled: false, granted: [], open: [] }), steps: ["Turn on…"] },
  "dialog-grant": { snap: snapshot(), term: terminal(), steps: ["Grant…"] },
  "dialog-off": { snap: snapshot(), term: terminal(), steps: ["On"] },
};

const params = new URLSearchParams(location.search);
const scenario = scenarios[params.get("state") ?? "on"] ?? scenarios.on;
window.location.hash = "/terminal";

const client = createClient({
  invoke: async (command) => {
    if (scenario.never) return new Promise(() => undefined);
    if (command === "snapshot") return scenario.snap;
    if (command === "terminal_status") return scenario.term ?? terminal();
    if (command === "terminal_enable" && scenario.fail) throw { kind: "cli-error", message: "cannot write terminal.json: permission denied", exit_code: 1 };
    return { kind: "status" };
  },
  listen: async () => () => undefined,
});

createRoot(document.getElementById("root")!).render(
  <I18nProvider locale={params.get("lang") === "id" ? "id" : "en"}>
    <IpcProvider client={client}>
      <SnapshotProvider>
        <Shell>
          <TerminalView />
        </Shell>
      </SnapshotProvider>
    </IpcProvider>
  </I18nProvider>,
);

function buttonNamed(name: string): HTMLElement | undefined {
  return [...document.querySelectorAll<HTMLElement>("button")].find((b) => {
    const labelled = b.getAttribute("aria-labelledby");
    return b.textContent === name || (labelled !== null && document.getElementById(labelled)?.textContent === name);
  });
}

async function runSteps() {
  for (const name of scenario.steps ?? []) {
    let target: HTMLElement | undefined;
    for (let i = 0; i < 50 && !target; i++) {
      await new Promise((r) => setTimeout(r, 40));
      target = buttonNamed(name);
    }
    target?.click();
  }
  document.body.dataset.ready = "1";
}
void runSteps();
