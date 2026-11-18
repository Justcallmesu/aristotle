import type { SnapshotDto } from "../ipc";
import type { MessageKey, Translator } from "../i18n";

export type Light = "ok" | "warn" | "err";

/** design.md 4.1 row numbers; 0 = no poll finished yet. */
export type Row = 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9;

export interface Mismatch {
  server: string;
  desktop: string;
}

export interface World {
  row: Row;
  titleKey: MessageKey;
  subKey: MessageKey | null;
  light: Light | null;
  /** P5/P17: set separately from the row; disables every action. */
  mismatch: Mismatch | null;
  installed: boolean;
  running: boolean;
  /** The server answers and the unit is `active`. */
  serviceActive: boolean;
}

const rows: Record<Row, { title: MessageKey; sub: MessageKey | null; light: Light | null }> = {
  0: { title: "st_loading", sub: null, light: null },
  1: { title: "st_nocli", sub: "sub_nocli", light: "err" },
  2: { title: "st_notinst", sub: "sub_notinst", light: "err" },
  3: { title: "st_noanswer", sub: "sub_noanswer", light: "err" },
  4: { title: "st_stopped", sub: "sub_stopped", light: "err" },
  5: { title: "st_outside", sub: "sub_outside", light: "err" },
  6: { title: "st_running", sub: "sub_ok", light: "ok" },
  7: { title: "st_running", sub: "sub_herm", light: "warn" },
  8: { title: "st_running", sub: "sub_hermerr", light: "warn" },
  9: { title: "st_running", sub: "sub_hermunk", light: "warn" },
};

function rowOf(snapshot: SnapshotDto | null): Row {
  if (!snapshot) return 0;
  const { status } = snapshot;
  if (status.kind === "cli-missing") return 1;
  // A poll that told us nothing (timeout, garbage) reads as "not answering", like the tray.
  if (status.kind === "failed") return 3;
  const s = status.status;
  if (!s.installed) return 2;
  if (s.server === "no-answer") return 3;
  if (s.server !== "running") return 4;
  if (s.service !== "active") return 5;
  const hermes = s.result?.hermes;
  if (!hermes) return 9;
  if (hermes.state === "reachable") return 6;
  return hermes.state === "not-running" ? 7 : 8;
}

export function deriveWorld(snapshot: SnapshotDto | null): World {
  const row = rowOf(snapshot);
  const { title, sub, light } = rows[row];
  const status = snapshot?.status.kind === "answered" ? snapshot.status.status : null;
  const mismatch = snapshot?.version.kind === "mismatch" ? snapshot.version : null;
  return {
    row,
    titleKey: title,
    subKey: sub,
    light,
    mismatch: mismatch && { server: mismatch.server, desktop: mismatch.desktop },
    installed: status?.installed ?? false,
    running: status?.server === "running",
    serviceActive: status?.server === "running" && status.service === "active",
  };
}

/** P18: on a version mismatch the footer is red with the mismatch word, like the tray; the banner stays. */
export function footerState(world: World): { light: Light | null; titleKey: MessageKey } {
  if (world.mismatch) return { light: "err", titleKey: "mm_title" };
  return { light: world.light, titleKey: world.titleKey };
}

/** Footer line 2 (design.md 3.1): `<version> · Hermes <word>` while running, else the systemctl word. */
export function footerDetail(snapshot: SnapshotDto | null, tr: Translator): string {
  if (!snapshot || snapshot.status.kind !== "answered") return "";
  const s = snapshot.status.status;
  if (s.server !== "running") return tr.word("svc_", s.service);
  const hermes = s.result?.hermes;
  const word = hermes ? tr.word("hs_", hermes.state) : tr.t("hs_unknown");
  const version = s.result?.version ?? "—";
  return `${version} · Hermes ${word.toLowerCase()}`;
}
