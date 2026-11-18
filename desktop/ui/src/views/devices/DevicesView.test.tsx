import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createClient, type Device, type SnapshotDto } from "../../ipc";
import { fakeTransport, renderWithApp, snapshotOf } from "../../test/fakes";
import { DevicesView } from "./DevicesView";

const NOW = new Date(2026, 10, 18, 14, 30, 0);
const iso = (d: Date) => d.toISOString();

const PIXEL: Device = {
  id: "d-aa11",
  name: "pixel",
  created_at: iso(new Date(2026, 9, 3, 8, 0)),
  last_seen_at: iso(new Date(2026, 10, 18, 14, 0)),
};
const TAB: Device = {
  id: "d-bb22",
  name: "tablet",
  created_at: iso(new Date(2026, 8, 9, 8, 0)),
  last_seen_at: iso(new Date(2026, 10, 17, 23, 50)),
};

function snap(devices: Device[] | null, patch: Partial<SnapshotDto> = {}) {
  return snapshotOf({ devices, terminal_granted: ["d-aa11"], ...patch });
}

function setup(snapshot: SnapshotDto, replies: Record<string, unknown> = {}, locale: "en" | "id" = "en") {
  const fake = fakeTransport({
    snapshot,
    start_intent: { kind: "status" },
    devices_revoke: PIXEL,
    ...replies,
  });
  renderWithApp(<DevicesView />, { client: createClient(fake.transport), locale });
  const revokeCalls = () => fake.calls.filter((c) => c.command === "devices_revoke");
  return { ...fake, revokeCalls };
}

beforeEach(() => {
  window.location.hash = "";
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(NOW);
});
afterEach(() => vi.useRealTimers());
const user = () => userEvent.setup();

describe("Devices: list", () => {
  it("shows name, id, paired date, relative last seen with the ISO on hover, and the grant tag", async () => {
    setup(snap([PIXEL, TAB]));
    const rows = await screen.findAllByRole("row");
    const pixel = within(rows[1]);
    expect(pixel.getByText("pixel")).toBeInTheDocument();
    expect(pixel.getByText("d-aa11")).toBeInTheDocument();
    expect(pixel.getByText("Oct 3, 2026")).toBeInTheDocument();
    const seen = pixel.getByText("30 min ago");
    expect(seen).toHaveAttribute("title", PIXEL.last_seen_at);
    expect(pixel.getByText("Granted")).toBeInTheDocument();
    const tablet = within(rows[2]);
    expect(tablet.getByText("Nov 17 23:50")).toHaveAttribute("title", TAB.last_seen_at);
    expect(tablet.queryByText("Granted")).not.toBeInTheDocument();
    expect(tablet.getByText("—", { selector: "td" })).toBeInTheDocument();
  });

  it("reads the grants from the snapshot and never calls terminal_status", async () => {
    const fake = setup(snap([PIXEL, TAB], { terminal_granted: ["d-bb22"] }));
    const rows = await screen.findAllByRole("row");
    expect(within(rows[1]).queryByText("Granted")).not.toBeInTheDocument();
    expect(within(rows[2]).getByText("Granted")).toBeInTheDocument();
    expect(fake.calls.filter((c) => c.command === "terminal_status")).toHaveLength(0);
  });

  it("keeps the CLI order", async () => {
    setup(snap([TAB, PIXEL]));
    const rows = await screen.findAllByRole("row");
    expect(within(rows[1]).getByText("tablet")).toBeInTheDocument();
    expect(within(rows[2]).getByText("pixel")).toBeInTheDocument();
  });

  it("writes Indonesian dates and words", async () => {
    setup(snap([PIXEL, TAB]), {}, "id");
    expect(await screen.findByText("30 menit lalu")).toBeInTheDocument();
    expect(screen.getByText("17 Nov 23:50")).toBeInTheDocument();
    expect(screen.getByText("3 Okt 2026")).toBeInTheDocument();
  });

  it("shows — for an unreadable last-seen and in the grant column when the poll has no grants", async () => {
    setup(snap([{ ...PIXEL, last_seen_at: "" }], { terminal_enabled: null, terminal_granted: [] }));
    const row = (await screen.findAllByRole("row"))[1];
    await act(async () => undefined);
    expect(within(row).getAllByText("—").length).toBeGreaterThanOrEqual(2);
    expect(within(row).queryByText("Granted")).not.toBeInTheDocument();
  });

  it("is quiet about the CLI: just now for a fresh last seen", async () => {
    setup(snap([{ ...PIXEL, last_seen_at: iso(new Date(2026, 10, 18, 14, 29, 40)) }]));
    expect(await screen.findByText("just now")).toBeInTheDocument();
  });
});

describe("Devices: states", () => {
  it("empty: cause, then the one action, which opens Pair", async () => {
    const u = user();
    setup(snap([]));
    expect(await screen.findByRole("heading", { name: "No phones paired" })).toBeInTheDocument();
    expect(screen.getByText(/A phone appears here after it pairs/)).toBeInTheDocument();
    const buttons = screen.getAllByRole("button", { name: "Pair a phone" });
    expect(buttons).toHaveLength(2); // header tonal + the empty state's primary
    await u.click(buttons[1]);
    expect(window.location.hash).toBe("#/pair");
  });

  it("the header Pair a phone opens Pair", async () => {
    const u = user();
    setup(snap([PIXEL]));
    await u.click(await screen.findByRole("button", { name: "Pair a phone" }));
    expect(window.location.hash).toBe("#/pair");
  });

  it("loading before the first poll", () => {
    const fake = fakeTransport({ snapshot: () => new Promise(() => undefined) });
    renderWithApp(<DevicesView />, { client: createClient(fake.transport) });
    expect(screen.getByText("Checking the server…")).toBeInTheDocument();
  });

  it("needs-server when the server is not running", async () => {
    setup(
      snap([], {
        status: {
          kind: "answered",
          status: { service: "inactive", enabled: "enabled", installed: true, server: "not-running", result: null },
        },
      }),
    );
    expect(await screen.findByRole("heading", { name: "Needs the running server" })).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start server" })).toBeEnabled();
  });

  it("not installed: needs-server without a Start button", async () => {
    setup(
      snap([], {
        status: {
          kind: "answered",
          status: { service: "unknown", enabled: "not-found", installed: false, server: "not-running", result: null },
        },
      }),
    );
    expect(await screen.findByRole("heading", { name: "Needs the running server" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Start server" })).not.toBeInTheDocument();
  });

  it("mismatch: Revoke is off and the reason sits under the table", async () => {
    setup(snap([PIXEL], { version: { kind: "mismatch", server: "1.0.0", desktop: "1.2.3" } }));
    expect(await screen.findByRole("button", { name: /Revoke/ })).toBeDisabled();
    expect(screen.getByText("Actions are off until the server is updated.")).toBeInTheDocument();
  });

  it("devices unread (null) shows —, not an empty-state lie", async () => {
    setup(snap(null));
    expect(await screen.findByText("—")).toBeInTheDocument();
    expect(screen.queryByText("No phones paired")).not.toBeInTheDocument();
  });
});

describe("Devices: revoke", () => {
  it("opens the D15 dialog on Cancel focus; Cancel sends nothing", async () => {
    const u = user();
    const { revokeCalls } = setup(snap([PIXEL]));
    await u.click(await screen.findByRole("button", { name: /Revoke/ }));
    const dialog = screen.getByRole("alertdialog", { name: "Revoke pixel?" });
    expect(within(dialog).getByText(/must pair again/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveFocus();
    await u.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(revokeCalls()).toHaveLength(0);
  });

  it("Escape sends nothing and focus returns to Revoke…", async () => {
    const u = user();
    const { revokeCalls } = setup(snap([PIXEL]));
    const opener = await screen.findByRole("button", { name: /Revoke/ });
    await u.click(opener);
    await u.keyboard("{Escape}");
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(revokeCalls()).toHaveLength(0);
    expect(opener).toHaveFocus();
  });

  it("confirm runs devices_revoke once with the id and does not delete the row itself", async () => {
    const u = user();
    const { revokeCalls } = setup(snap([PIXEL, TAB]));
    await u.click((await screen.findAllByRole("button", { name: /Revoke/ }))[0]);
    await u.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Revoke" }));
    await waitFor(() => expect(revokeCalls()).toHaveLength(1));
    expect(revokeCalls()[0].args).toEqual({ id: "d-aa11" });
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(screen.getByText("pixel")).toBeInTheDocument(); // gone only on the next snapshot
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("the row disappears when the next snapshot no longer lists it", async () => {
    const u = user();
    const { emit } = setup(snap([PIXEL, TAB]));
    await u.click((await screen.findAllByRole("button", { name: /Revoke/ }))[0]);
    await u.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Revoke" }));
    await act(async () => emit("snapshot", snap([TAB], { seq: 2 })));
    await waitFor(() => expect(screen.queryByText("pixel")).not.toBeInTheDocument());
    expect(screen.getByText("tablet")).toBeInTheDocument();
  });

  it("a failed revoke shows CommandFailed and keeps the row", async () => {
    const u = user();
    const fake = fakeTransport({
      snapshot: snap([PIXEL]),
      start_intent: { kind: "status" },
      devices_revoke: () => Promise.reject({ kind: "cli", message: "no such device", exit_code: 1 }),
    });
    renderWithApp(<DevicesView />, { client: createClient(fake.transport) });
    await u.click(await screen.findByRole("button", { name: /Revoke/ }));
    await u.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Revoke" }));
    const alert = await screen.findByRole("alert");
    expect(within(alert).getByText("error: no such device")).toBeInTheDocument();
    expect((document.querySelector(".view-header") as HTMLElement).nextElementSibling).toBe(alert);
    expect(alert.parentElement?.classList.contains("banner-slot")).toBe(false);
    expect(screen.getByText("pixel")).toBeInTheDocument();
  });
});
