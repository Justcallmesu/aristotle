import { createRoot } from "react-dom/client";
import { Shell } from "../../../components/Shell";
import { I18nProvider } from "../../../i18n";
import { createClient, IpcProvider, type StartIntent, type SnapshotDto, type Transport } from "../../../ipc";
import { IntentProvider } from "../../../state/intent";
import { SnapshotProvider } from "../../../state/snapshot";
import "../../../styles/index.css";
import { LogsView } from "../../logs/LogsView";
import { ServiceView } from "../../service/ServiceView";
import { StatusView } from "../StatusView";
import { hermes, LOG_LINES, mismatch, snapshot, stopped } from "./fixtures";

/**
 * Dev-only state page for the DS4 screenshots: `shots/index.html?s=<state>`. A fake transport
 * stands in for the backend; nothing talks to a real server.
 */
interface Scene {
  view: "status" | "service" | "logs";
  snapshot: SnapshotDto | null;
  intent?: StartIntent;
  /** Text of a button to press once the view is up. */
  click?: string;
  /** What `service_action` does after the click. */
  action?: "hang" | "fail";
  lines?: string[];
  ended?: boolean;
  startFails?: boolean;
  /** After the first lines: scroll up and send more. */
  paused?: boolean;
  noWrap?: boolean;
}

const running = snapshot();
const SCENES: Record<string, Scene> = {
  "status-loading": { view: "status", snapshot: null },
  "status-running": { view: "status", snapshot: running },
  "status-hermes-down": { view: "status", snapshot: hermes("not-running") },
  "status-hermes-error": { view: "status", snapshot: hermes("error", "socket /run/user/1000/hermes.sock: connection refused") },
  "status-hermes-unknown": { view: "status", snapshot: hermes(null) },
  "status-no-answer": { view: "status", snapshot: snapshot({ server: "no-answer", result: null }) },
  "status-stopped": { view: "status", snapshot: stopped() },
  "status-outside": { view: "status", snapshot: snapshot({ service: "unknown" }) },
  "status-not-installed": { view: "status", snapshot: stopped({ installed: false }) },
  "status-no-cli": { view: "status", snapshot: snapshot({}, { status: { kind: "cli-missing" } }) },
  "status-mismatch": { view: "status", snapshot: mismatch() },
  "status-activating": { view: "status", snapshot: stopped({ service: "activating" }) },
  "status-start-working": { view: "status", snapshot: stopped(), click: "Start server", action: "hang" },
  "status-start-failed": { view: "status", snapshot: stopped(), click: "Start server", action: "fail" },
  "service-running": { view: "service", snapshot: running },
  "service-stopped": { view: "service", snapshot: stopped() },
  "service-other-word": { view: "service", snapshot: stopped({ service: "activating" }) },
  "service-no-answer": { view: "service", snapshot: snapshot({ server: "no-answer", result: null }) },
  "service-not-installed": { view: "service", snapshot: stopped({ installed: false }) },
  "service-mismatch": { view: "service", snapshot: mismatch() },
  "service-stop-dialog": { view: "service", snapshot: running, click: "Stop server" },
  "service-stop-intent": { view: "service", snapshot: running, intent: { kind: "confirm-stop" } },
  "service-restart-dialog": { view: "service", snapshot: running, click: "Restart server" },
  "service-working": { view: "service", snapshot: running, click: "Restart server", action: "hang" },
  "service-failed": { view: "service", snapshot: running, click: "Restart server", action: "fail" },
  "logs-live": { view: "logs", snapshot: running, lines: LOG_LINES },
  "logs-paused": { view: "logs", snapshot: running, lines: LOG_LINES, paused: true },
  "logs-nowrap": { view: "logs", snapshot: running, lines: LOG_LINES, noWrap: true },
  "logs-stopped": { view: "logs", snapshot: stopped(), lines: LOG_LINES },
  "logs-ended": { view: "logs", snapshot: running, lines: LOG_LINES, ended: true },
  "logs-start-failed": { view: "logs", snapshot: running, startFails: true },
  "logs-empty": { view: "logs", snapshot: running, lines: [] },
};

type Handler = (payload: unknown) => void;

function transportFor(scene: Scene): Transport {
  const handlers = new Map<string, Set<Handler>>();
  const emit = (event: string, payload: unknown) => handlers.get(event)?.forEach((h) => h(payload));
  return {
    listen: async (event, handler) => {
      const set = handlers.get(event) ?? new Set<Handler>();
      set.add(handler);
      handlers.set(event, set);
      return () => set.delete(handler);
    },
    invoke: (command) => {
      switch (command) {
        case "snapshot":
          return scene.snapshot ? Promise.resolve(scene.snapshot) : new Promise(() => undefined);
        case "start_intent":
          return Promise.resolve(scene.intent ?? { kind: "status" });
        case "terminal_status":
          return Promise.resolve({ enabled: true, granted: [{ id: "dev_a1b2c3d4e5f6", name: "pixel" }] });
        case "service_action":
          return scene.action === "fail"
            ? Promise.reject({ kind: "server-error", message: "Failed to start aristotle-server.service: Unit is masked.", exit_code: 1 })
            : new Promise(() => undefined);
        case "logs_start":
          if (scene.startFails) return Promise.reject({ kind: "io", message: null, exit_code: null });
          window.setTimeout(() => runLogScene(scene, emit), 60);
          return Promise.resolve(null);
        default:
          return Promise.resolve(null);
      }
    },
  };
}

function runLogScene(scene: Scene, emit: (event: string, payload: unknown) => void) {
  if (scene.lines?.length) emit("logs-lines", { lines: scene.lines });
  if (scene.paused) {
    window.setTimeout(() => {
      const box = document.querySelector<HTMLElement>('[role="log"]');
      if (!box) return;
      box.scrollTop = 0;
      box.dispatchEvent(new Event("scroll"));
      emit("logs-lines", { lines: LOG_LINES.slice(0, 7) });
    }, 150);
  }
  if (scene.ended) window.setTimeout(() => emit("logs-ended", null), 100);
}

function pressLater(label: string) {
  const timer = window.setInterval(() => {
    const target = Array.from(document.querySelectorAll("button")).find((b) => b.textContent === label && !b.disabled);
    if (!target) return;
    window.clearInterval(timer);
    target.click();
  }, 100);
}

/** A switch is a button named by its label span, so find it by that name. */
function toggleLater(label: string) {
  const timer = window.setInterval(() => {
    const named = (el: HTMLElement) => document.getElementById(el.getAttribute("aria-labelledby") ?? "")?.textContent === label;
    const target = Array.from(document.querySelectorAll<HTMLElement>('[role="switch"]')).find(named);
    if (!target) return;
    window.clearInterval(timer);
    target.click();
  }, 100);
}

const params = new URLSearchParams(window.location.search);
const scene = SCENES[params.get("s") ?? "status-running"];
const View = { status: StatusView, service: ServiceView, logs: LogsView }[scene.view];
window.location.hash = `/${scene.view}`;
document.documentElement.lang = params.get("lang") ?? "en";

if (scene.click) pressLater(scene.click);
if (scene.noWrap) toggleLater("Wrap lines");

createRoot(document.getElementById("root")!).render(
  <I18nProvider locale={params.get("lang") === "id" ? "id" : "en"}>
    <IpcProvider client={createClient(transportFor(scene))}>
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
