import { snapshotOf } from "../test/fakes";
import { deriveWorld, footerDetail, type Row } from "./world";
import { createTranslator } from "../i18n";
import type { SnapshotDto, Status } from "../ipc";

function withStatus(patch: Partial<Status>): SnapshotDto {
  const base = snapshotOf();
  if (base.status.kind !== "answered") throw new Error("fixture");
  return { ...base, status: { kind: "answered", status: { ...base.status.status, ...patch } } };
}

function hermes(state: string | null): SnapshotDto {
  const base = withStatus({});
  if (base.status.kind !== "answered") throw new Error("fixture");
  const result = base.status.status.result!;
  return withStatus({ result: { ...result, hermes: state ? { state, reason: null } : null } });
}

describe("deriveWorld follows design.md 4.1", () => {
  const cases: [string, SnapshotDto | null, Row, string | null][] = [
    ["no poll yet", null, 0, null],
    ["CLI missing", snapshotOf({ status: { kind: "cli-missing" } }), 1, "err"],
    ["not installed", withStatus({ installed: false, server: "not-running", result: null }), 2, "err"],
    ["no answer", withStatus({ server: "no-answer", result: null }), 3, "err"],
    ["failed poll", snapshotOf({ status: { kind: "failed" } }), 3, "err"],
    ["stopped", withStatus({ server: "not-running", service: "inactive", result: null }), 4, "err"],
    ["changing: activating", withStatus({ server: "not-running", service: "activating", result: null }), "4b", "err"],
    ["changing: deactivating", withStatus({ server: "not-running", service: "deactivating", result: null }), "4b", "err"],
    ["stopped: failed", withStatus({ server: "not-running", service: "failed", result: null }), 4, "err"],
    ["stopped: service word absent", withStatus({ server: "not-running", service: "", result: null }), 4, "err"],
    ["a word a newer server adds (core sends \"other\")", withStatus({ server: "other", result: null }), 4, "err"],
    ["running outside the service", withStatus({ service: "unknown" }), 5, "err"],
    ["running, hermes reachable", hermes("reachable"), 6, "ok"],
    ["running, hermes not running", hermes("not-running"), 7, "warn"],
    ["running, hermes error", hermes("error"), 8, "warn"],
    ["running, any other hermes word", hermes("exploded"), 8, "warn"],
    ["running, hermes absent", hermes(null), 9, "warn"],
  ];
  it.each(cases)("%s", (_name, snapshot, row, light) => {
    const world = deriveWorld(snapshot);
    expect(world.row).toBe(row);
    expect(world.light).toBe(light);
  });

  it("not installed wins over a stopped server", () => {
    expect(deriveWorld(withStatus({ installed: false, server: "not-running" })).row).toBe(2);
  });

  it("flags a version mismatch separately from the row", () => {
    const world = deriveWorld(snapshotOf({ version: { kind: "mismatch", server: "1.2.2", desktop: "1.2.3" } }));
    expect(world.mismatch).toEqual({ server: "1.2.2", desktop: "1.2.3" });
    expect(world.row).toBe(6);
  });
});

describe("row 4b (P23)", () => {
  it("carries the raw systemd word for the sub line", () => {
    const world = deriveWorld(withStatus({ server: "not-running", service: "reloading", result: null }));
    expect(world.titleKey).toBe("st_changing");
    expect(world.subKey).toBe("sub_changing");
    expect(world.subParams).toEqual({ word: "reloading" });
  });
});

describe("footerDetail", () => {
  const tr = createTranslator("en");
  it("shows version and the hermes word while running", () => {
    expect(footerDetail(hermes("reachable"), tr)).toBe("1.2.3 · Hermes reachable");
    expect(footerDetail(hermes(null), tr)).toBe("1.2.3 · Hermes unknown");
  });
  it("shows the systemctl word otherwise", () => {
    const stopped = withStatus({ server: "not-running", service: "inactive", result: null });
    expect(footerDetail(stopped, tr)).toBe("inactive");
  });
});
