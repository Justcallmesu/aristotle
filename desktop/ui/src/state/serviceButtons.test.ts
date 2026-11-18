import { snapshotOf } from "../test/fakes";
import { withStatus } from "../test/views";
import { statusOf } from "./facts";
import { canStart, serviceButtons } from "./serviceButtons";
import { deriveWorld } from "./world";

const WORDS = ["active", "inactive", "failed", "activating", "deactivating", "reloading", "unknown", "maintenance"];
const START_WORDS = ["inactive", "failed"];
const result = { version: "1", addresses: [], port: 1, listeners: null, hermes: null };

function inputs(service: string, patch: Parameters<typeof withStatus>[0] = {}, rest: Parameters<typeof withStatus>[1] = {}) {
  const server = patch.server ?? "not-running";
  const snapshot = withStatus({ service, server, result: server === "running" ? result : null, ...patch }, rest);
  return { status: statusOf(snapshot), world: deriveWorld(snapshot) };
}

describe("canStart (P21): the one Start rule", () => {
  it.each(WORDS)("service %s: Start only for inactive and failed", (service) => {
    const { status, world } = inputs(service);
    expect(canStart(status, world)).toBe(START_WORDS.includes(service));
  });

  it("is off with no answer, not installed, or a version mismatch", () => {
    expect(canStart(null, deriveWorld(null))).toBe(false);
    const gone = inputs("inactive", { installed: false });
    expect(canStart(gone.status, gone.world)).toBe(false);
    const mismatch = inputs("inactive", {}, { version: { kind: "mismatch", server: "1", desktop: "2" } });
    expect(canStart(mismatch.status, mismatch.world)).toBe(false);
    const stale = snapshotOf({ version: { kind: "mismatch", server: "1", desktop: "2" } });
    expect(canStart(statusOf(stale), deriveWorld(stale))).toBe(false);
  });
});

describe("serviceButtons", () => {
  it.each(WORDS)("service %s: Start follows canStart; Stop and Restart only on active", (service) => {
    const { status, world } = inputs(service);
    const buttons = serviceButtons(status, world, false);
    expect(buttons.start).toBe(canStart(status, world));
    expect(buttons.stop).toBe(service === "active");
    expect(buttons.restart).toBe(service === "active");
  });

  it.each(["activating", "deactivating", "reloading", "unknown", "maintenance"])(
    "service %s: all off, written reason dis_other with the word",
    (service) => {
      const { status, world } = inputs(service);
      expect(serviceButtons(status, world, false)).toEqual({
        start: false,
        stop: false,
        restart: false,
        reason: { key: "dis_other", params: { word: service } },
      });
    },
  );

  it("a running command turns all off without a reason", () => {
    const { status, world } = inputs("inactive");
    expect(serviceButtons(status, world, true)).toEqual({ start: false, stop: false, restart: false, reason: null });
  });
});
