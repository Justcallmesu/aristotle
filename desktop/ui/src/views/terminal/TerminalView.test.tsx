import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { createClient, type Device, type SnapshotDto, type TerminalStatus } from "../../ipc";
import { fakeTransport, renderWithApp, snapshotOf } from "../../test/fakes";
import { TerminalView } from "./TerminalView";

const phone = (id: string, name: string): Device => ({ id, name, created_at: "2026-11-01T08:00:00Z", last_seen_at: "2026-11-18T08:00:00Z" });
const pixel = phone("dev-1", "pixel");
const tab = phone("dev-2", "tablet");

function status(overrides: Partial<TerminalStatus> = {}): TerminalStatus {
  return { enabled: true, granted: [], open: [], changed: false, warning: null, problem: null, device: null, ...overrides };
}

function stopped(server: string, installed = true): SnapshotDto {
  const base = snapshotOf();
  if (base.status.kind !== "answered") throw new Error("fixture");
  return { ...base, status: { kind: "answered", status: { ...base.status.status, server, installed, service: "inactive", result: null } } };
}

interface Setup {
  snapshot?: SnapshotDto;
  terminal?: TerminalStatus;
  replies?: Record<string, unknown>;
}

/** The poll lists the same grants as the server's `terminal status`, unless the test sets its own. */
async function setup({ snapshot = snapshotOf({ devices: [pixel, tab] }), terminal = status(), replies = {} }: Setup = {}) {
  const ids = terminal.granted.map((g) => g.id);
  if (snapshot.terminal_granted !== null && snapshot.terminal_granted.length === 0) snapshot = { ...snapshot, terminal_granted: ids };
  const fake = fakeTransport({ snapshot, start_intent: { kind: "status" }, terminal_status: terminal, ...replies });
  renderWithApp(<TerminalView />, { client: createClient(fake.transport) });
  await screen.findByRole("heading", { name: "Remote terminal", level: 1 });
  return fake;
}

const sent = (fake: { calls: { command: string; args?: Record<string, unknown> }[] }, command: string) => fake.calls.filter((c) => c.command === command);
const terminalCommands = (fake: { calls: { command: string }[] }) =>
  fake.calls.map((c) => c.command).filter((c) => /^terminal_(enable|disable|grant|ungrant)$/.test(c));

describe("Terminal view states", () => {
  it("loading: says so until the first poll", async () => {
    const fake = fakeTransport({ snapshot: () => new Promise(() => undefined) });
    renderWithApp(<TerminalView />, { client: createClient(fake.transport) });
    expect(await screen.findByRole("status")).toHaveTextContent("Checking the server…");
  });

  it("needs-server: stopped server shows the cause and Start server", async () => {
    await setup({ snapshot: stopped("not-running") });
    expect(await screen.findByText("Needs the running server")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start server" })).toBeInTheDocument();
    expect(screen.queryByRole("switch")).toBeNull();
  });

  it("not-installed: needs-server without a Start button", async () => {
    await setup({ snapshot: stopped("not-running", false) });
    expect(await screen.findByText("Needs the running server")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Start server" })).toBeNull();
  });

  it("off: switch off, description, Turn on… and no open group", async () => {
    await setup({ terminal: status({ enabled: false }) });
    expect(await screen.findByRole("switch", { name: "Off" })).toHaveAttribute("aria-checked", "false");
    expect(screen.getByText("No phone can open a shell.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Turn on…" })).toBeEnabled();
    expect(screen.queryByText("Open terminals")).toBeNull();
    expect(screen.getByText("Granted phones")).toBeInTheDocument();
  });

  it("on: switch on, hint, grants and open terminals with the device name", async () => {
    const since = new Date(Date.now() - 12 * 60_000).toISOString();
    await setup({
      terminal: status({
        granted: [{ id: "dev-1", name: "pixel" }],
        open: [{ id: "term-9", device_id: "dev-1", listener: "tailscale", since, pid: 4242 }],
      }),
      snapshot: snapshotOf({ devices: [pixel, tab], terminals: [{ id: "term-9", device_id: "dev-1", listener: "tailscale", since, pid: 4242 }] }),
    });
    expect(await screen.findByRole("switch", { name: "On" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByText("Turning off closes open terminals and ends their processes.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Turn on…" })).toBeNull();
    const open = screen.getByRole("region", { name: "Open terminals" });
    expect(within(open).getByText("pixel")).toBeInTheDocument();
    expect(within(open).getByText(/Tailscale · \d\d:\d\d · 12 min · PID 4242/)).toBeInTheDocument();
    expect(within(open).getByText("term-9")).toBeInTheDocument();
  });

  it("on with nothing open and nothing granted: both empty rows", async () => {
    await setup();
    expect(await screen.findByText("No terminal is open.")).toBeInTheDocument();
    expect(screen.getByText("No phone holds a grant. Grant one below.")).toBeInTheDocument();
  });

  it("an open terminal of an unknown device shows the raw id", async () => {
    const since = new Date().toISOString();
    const open = [{ id: "t1", device_id: "ghost-id", listener: "lan", since, pid: 1 }];
    await setup({ terminal: status({ open }), snapshot: snapshotOf({ devices: [pixel], terminals: open }) });
    expect(await screen.findByText("ghost-id")).toBeInTheDocument();
  });

  it("a grant without a name falls back to the devices list, then to 'no longer paired'", async () => {
    await setup({ terminal: status({ granted: [{ id: "dev-1", name: "" }, { id: "dev-x", name: "" }] }) });
    const grants = await screen.findByRole("region", { name: "Granted phones" });
    expect(within(grants).getByText("pixel")).toBeInTheDocument();
    expect(within(grants).getByText("(no longer paired)")).toBeInTheDocument();
  });

  it("add row lists only phones without a grant; none left shows the all-granted line", async () => {
    await setup({ terminal: status({ granted: [{ id: "dev-1", name: "pixel" }] }) });
    const select = await screen.findByRole("combobox", { name: "Grant to" });
    expect(within(select).getAllByRole("option").map((o) => o.textContent)).toEqual(["tablet"]);
  });

  it("every phone granted: the add row is a sentence", async () => {
    await setup({ terminal: status({ granted: [{ id: "dev-1", name: "pixel" }, { id: "dev-2", name: "tablet" }] }) });
    expect(await screen.findByText("Every paired phone already has a grant.")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).toBeNull();
  });

  it("no paired phone: no add row", async () => {
    await setup({ snapshot: snapshotOf({ devices: [] }) });
    await screen.findByText("Granted phones");
    expect(screen.queryByRole("combobox")).toBeNull();
    expect(screen.queryByText("Every paired phone already has a grant.")).toBeNull();
  });

  it("warning: a status banner, content stays usable", async () => {
    await setup({ terminal: status({ warning: "file mode is 0644" }) });
    const banner = await screen.findByText("Saved, with a warning").then((el) => el.closest("[role=status]") as HTMLElement);
    expect(banner).toHaveTextContent("Saved, with a warning");
    expect(banner).toHaveTextContent("warning: file mode is 0644");
    expect(screen.getByRole("switch")).not.toHaveAttribute("aria-disabled");
  });

  it("problem: only the alert, switch disabled, no lists, Check again re-reads", async () => {
    const fake = await setup({ terminal: status({ enabled: false, problem: "bad json at line 3" }) });
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("terminal.json can’t be read");
    expect(alert).toHaveTextContent("problem: bad json at line 3");
    expect(screen.getByRole("switch")).toHaveAttribute("aria-disabled", "true");
    expect(screen.queryByText("Granted phones")).toBeNull();
    expect(screen.queryByRole("button", { name: "Turn on…" })).toBeNull();
    const before = sent(fake, "terminal_status").length;
    await userEvent.click(screen.getByRole("button", { name: "Check again" }));
    await waitFor(() => expect(sent(fake, "terminal_status").length).toBe(before + 1));
  });

  it("mismatch: switch, Turn on…, Grant… and Remove grant are off, with the written reason", async () => {
    await setup({
      snapshot: snapshotOf({ devices: [pixel, tab], version: { kind: "mismatch", server: "1.0.0", desktop: "1.2.3" } }),
      terminal: status({ granted: [{ id: "dev-1", name: "pixel" }] }),
    });
    expect(await screen.findByRole("switch")).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("button", { name: "Grant…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Remove grant" })).toBeDisabled();
    expect(screen.getByText("Actions are off until the server is updated.")).toBeInTheDocument();
  });

  it("mismatch with the switch off: Turn on… is off", async () => {
    await setup({
      snapshot: snapshotOf({ version: { kind: "mismatch", server: "1.0.0", desktop: "1.2.3" } }),
      terminal: status({ enabled: false }),
    });
    expect(await screen.findByRole("button", { name: "Turn on…" })).toBeDisabled();
  });

  it("a failed read keeps the view usable and shows — where there is no data", async () => {
    const fake = fakeTransport({ snapshot: snapshotOf({ devices: [pixel] }), start_intent: { kind: "status" } });
    renderWithApp(<TerminalView />, { client: createClient(fake.transport) });
    expect(await screen.findByText("Granted phones")).toBeInTheDocument();
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
    expect(screen.getByRole("switch")).toHaveAttribute("aria-disabled", "true");
  });

  it("command failed: alert with the server's text; the dialog was confirmed once", async () => {
    const fake = await setup({ terminal: status({ enabled: false }) });
    fake.setReply("terminal_enable", () => {
      throw { kind: "cli-error", message: "disk full", exit_code: 1 };
    });
    await userEvent.click(await screen.findByRole("button", { name: "Turn on…" }));
    await userEvent.click(screen.getByRole("button", { name: "Turn on remote terminal" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("That did not work");
    expect(alert).toHaveTextContent("error: disk full");
    expect(sent(fake, "terminal_enable")).toHaveLength(1);
  });
});

describe("Terminal view: D7 enable dialog", () => {
  it("has the warning, a red confirm, no remember option, focus on Cancel", async () => {
    await setup({ terminal: status({ enabled: false }) });
    await userEvent.click(await screen.findByRole("button", { name: "Turn on…" }));
    const dialog = screen.getByRole("alertdialog", { name: "Turn on the remote terminal?" });
    expect(within(dialog).getByText(/FULL access to your account/)).toBeInTheDocument();
    expect(within(dialog).getByText("No phone holds a grant yet. You grant phones one by one.")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Turn on remote terminal" })).toHaveClass("btn-danger");
    expect(within(dialog).queryByRole("checkbox")).toBeNull();
    expect(within(dialog).queryByText(/ask again|remember/i)).toBeNull();
    expect(within(dialog).getAllByRole("button").map((b) => b.textContent)).toEqual(["Cancel", "Turn on remote terminal"]);
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveFocus();
  });

  it("leaves out the no-grant line when a phone already holds a grant", async () => {
    await setup({ terminal: status({ enabled: false, granted: [{ id: "dev-1", name: "pixel" }] }) });
    await userEvent.click(await screen.findByRole("button", { name: "Turn on…" }));
    expect(screen.queryByText(/No phone holds a grant yet/)).toBeNull();
  });

  it("Cancel and Esc send nothing", async () => {
    const fake = await setup({ terminal: status({ enabled: false }) });
    const opener = await screen.findByRole("button", { name: "Turn on…" });
    await userEvent.click(opener);
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    await userEvent.click(opener);
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(terminalCommands(fake)).toEqual([]);
  });

  it("the switch off→on opens the same dialog; confirm runs terminal_enable and shows the result", async () => {
    const fake = await setup({ terminal: status({ enabled: false }) });
    fake.setReply("terminal_enable", status({ enabled: true, warning: "heads up" }));
    await userEvent.click(await screen.findByRole("switch", { name: "Off" }));
    expect(screen.getByRole("alertdialog", { name: "Turn on the remote terminal?" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Turn on remote terminal" }));
    expect(await screen.findByRole("switch", { name: "On" })).toBeInTheDocument();
    expect(sent(fake, "terminal_enable")).toHaveLength(1);
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(screen.getByRole("status")).toHaveTextContent("warning: heads up");
  });
});

describe("Terminal view: D7 grant dialog", () => {
  const granted = status({ granted: [{ id: "dev-1", name: "pixel" }] });

  it("names the phone, warns, red confirm, no remember; Cancel sends nothing", async () => {
    const fake = await setup({ terminal: granted });
    await userEvent.click(await screen.findByRole("button", { name: "Grant…" }));
    const dialog = screen.getByRole("alertdialog", { name: "Give tablet a terminal?" });
    expect(within(dialog).getByText(/FULL access/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Grant full access" })).toHaveClass("btn-danger");
    expect(within(dialog).queryByRole("checkbox")).toBeNull();
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveFocus();
    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(terminalCommands(fake)).toEqual([]);
  });

  it("confirm grants the selected phone's id", async () => {
    const fake = await setup({
      terminal: status({ granted: [] }),
      replies: { terminal_grant: status({ granted: [{ id: "dev-2", name: "tablet" }] }) },
    });
    await userEvent.selectOptions(await screen.findByRole("combobox", { name: "Grant to" }), "dev-2");
    await userEvent.click(screen.getByRole("button", { name: "Grant…" }));
    await userEvent.click(screen.getByRole("button", { name: "Grant full access" }));
    await waitFor(() => expect(sent(fake, "terminal_grant")).toHaveLength(1));
    expect(sent(fake, "terminal_grant")[0].args).toEqual({ id: "dev-2" });
    expect(await within(await screen.findByRole("region", { name: "Granted phones" })).findByText("tablet")).toBeInTheDocument();
  });
});

describe("Terminal view: turn off and remove grant", () => {
  const open = [{ id: "t1", device_id: "dev-1", listener: "lan", since: new Date().toISOString(), pid: 7 }];
  const snapshot = snapshotOf({ devices: [pixel], terminals: open });

  it("with open terminals it confirms; Cancel sends nothing", async () => {
    const fake = await setup({ snapshot, terminal: status({ open }) });
    await userEvent.click(await screen.findByRole("switch", { name: "On" }));
    const dialog = screen.getByRole("alertdialog", { name: "Turn off the remote terminal?" });
    expect(within(dialog).getByText("Open terminals: 1. Turning off closes them and ends their processes.")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Turn off" })).toHaveClass("btn-danger");
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveFocus();
    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(terminalCommands(fake)).toEqual([]);
    expect(screen.getByRole("switch", { name: "On" })).toBeInTheDocument();
  });

  it("with open terminals, confirm runs terminal_disable once", async () => {
    const fake = await setup({ snapshot, terminal: status({ open }), replies: { terminal_disable: status({ enabled: false }) } });
    await userEvent.click(await screen.findByRole("switch", { name: "On" }));
    await userEvent.click(screen.getByRole("button", { name: "Turn off" }));
    expect(await screen.findByRole("switch", { name: "Off" })).toBeInTheDocument();
    expect(sent(fake, "terminal_disable")).toHaveLength(1);
  });

  it("a terminal opened since the last poll still gets the D19-8 dialog (fresh read)", async () => {
    const fake = await setup({ snapshot: snapshotOf({ devices: [pixel], terminals: [] }), terminal: status() });
    await screen.findByRole("switch", { name: "On" });
    fake.setReply("terminal_status", status({ open }));
    await userEvent.click(screen.getByRole("switch", { name: "On" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Turn off the remote terminal?" });
    expect(within(dialog).getByText("Open terminals: 1. Turning off closes them and ends their processes.")).toBeInTheDocument();
    expect(sent(fake, "terminal_disable")).toHaveLength(0);
  });

  it("when the fresh read fails it falls back to the polled list", async () => {
    const fake = await setup({ snapshot, terminal: status({ open }) });
    await screen.findByRole("switch", { name: "On" });
    fake.setReply("terminal_status", () => {
      throw { kind: "timeout", message: null, exit_code: null };
    });
    await userEvent.click(screen.getByRole("switch", { name: "On" }));
    expect(await screen.findByRole("alertdialog", { name: "Turn off the remote terminal?" })).toBeInTheDocument();
  });

  it("with none open it runs at once, no dialog", async () => {
    const fake = await setup({ terminal: status(), replies: { terminal_disable: status({ enabled: false }) } });
    await userEvent.click(await screen.findByRole("switch", { name: "On" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(await screen.findByRole("switch", { name: "Off" })).toBeInTheDocument();
    expect(sent(fake, "terminal_disable")).toHaveLength(1);
  });

  it("Remove grant runs directly with the id", async () => {
    const fake = await setup({ terminal: status({ granted: [{ id: "dev-1", name: "pixel" }] }) });
    // The backend publishes a fresh poll before it replies to a change.
    fake.setReply("terminal_ungrant", () => {
      fake.emit("snapshot", snapshotOf({ devices: [pixel, tab], terminal_granted: [], seq: 2 }));
      return status({ granted: [] });
    });
    await userEvent.click(await screen.findByRole("button", { name: "Remove grant" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(await screen.findByText("No phone holds a grant. Grant one below.")).toBeInTheDocument();
    expect(sent(fake, "terminal_ungrant")[0].args).toEqual({ id: "dev-1" });
  });
});

describe("Terminal view: grants and banners", () => {
  it("lists the grants from the poll, not from the terminal status read", async () => {
    await setup({
      snapshot: snapshotOf({ devices: [pixel, tab], terminal_granted: ["dev-2"] }),
      terminal: status({ granted: [{ id: "dev-1", name: "pixel" }] }),
    });
    const grants = await screen.findByRole("region", { name: "Granted phones" });
    expect(within(grants).getByText("tablet")).toBeInTheDocument();
    expect(within(grants).queryByText("pixel", { selector: "strong" })).toBeNull();
  });

  it("shows — for the grants when neither the poll nor the view's own read could read them", async () => {
    await setup({
      snapshot: snapshotOf({ devices: [pixel], terminal_enabled: null, terminal_granted: null }),
      replies: {
        terminal_status: () => {
          throw { kind: "timeout", message: null, exit_code: null };
        },
      },
    });
    const grants = await screen.findByRole("region", { name: "Granted phones" });
    expect(within(grants).getByText("—")).toBeInTheDocument();
  });

  it("falls back to its own terminal status read when the poll could not read the grants", async () => {
    await setup({
      snapshot: snapshotOf({ devices: [pixel, tab], terminal_enabled: null, terminal_granted: null }),
      terminal: status({ enabled: false, granted: [{ id: "dev-1", name: "pixel" }] }),
    });
    const grants = await screen.findByRole("region", { name: "Granted phones" });
    expect(within(grants).getByText("pixel", { selector: "strong" })).toBeInTheDocument();
    expect(within(grants).queryByText("No phone holds a grant. Grant one below.")).toBeNull();
    await userEvent.click(await screen.findByRole("button", { name: "Turn on…" }));
    expect(screen.queryByText(/No phone holds a grant yet/)).toBeNull();
  });

  it("a poll that fails to read the grants after a good one keeps the grant rows", async () => {
    const fake = await setup({
      snapshot: snapshotOf({ devices: [pixel, tab], terminal_granted: ["dev-1"] }),
      terminal: status({ granted: [{ id: "dev-1", name: "pixel" }] }),
    });
    const grants = await screen.findByRole("region", { name: "Granted phones" });
    expect(within(grants).getByText("pixel", { selector: "strong" })).toBeInTheDocument();
    act(() => {
      fake.emit("snapshot", snapshotOf({ devices: [pixel, tab], terminal_enabled: null, terminal_granted: null, seq: 2 }));
    });
    await waitFor(() => expect(sent(fake, "terminal_status").length).toBeGreaterThan(1));
    expect(within(grants).getByText("pixel", { selector: "strong" })).toBeInTheDocument();
    expect(within(grants).queryByText("—")).toBeNull();
  });

  it("the server's own empty name is 'no longer paired', even when the devices read failed", async () => {
    await setup({
      snapshot: snapshotOf({ devices: null, terminal_granted: ["dev-x"] }),
      terminal: status({ granted: [{ id: "dev-x", name: "" }] }),
    });
    const grants = await screen.findByRole("region", { name: "Granted phones" });
    expect(within(grants).getByText("(no longer paired)")).toBeInTheDocument();
    expect(within(grants).getAllByText("dev-x")).toHaveLength(1);
  });

  it("the server's own name wins, also when the devices read failed", async () => {
    await setup({
      snapshot: snapshotOf({ devices: null, terminal_granted: ["dev-x"] }),
      terminal: status({ granted: [{ id: "dev-x", name: "pixel" }] }),
    });
    const grants = await screen.findByRole("region", { name: "Granted phones" });
    expect(within(grants).getByText("pixel", { selector: "strong" })).toBeInTheDocument();
    expect(within(grants).queryByText("(no longer paired)")).toBeNull();
  });

  it("a failed devices read and no own read: the id is shown once, not 'no longer paired'", async () => {
    await setup({
      snapshot: snapshotOf({ devices: null, terminal_granted: ["dev-x"] }),
      replies: {
        terminal_status: () => {
          throw { kind: "timeout", message: null, exit_code: null };
        },
      },
    });
    const grants = await screen.findByRole("region", { name: "Granted phones" });
    expect(within(grants).queryByText("(no longer paired)")).toBeNull();
    expect(within(grants).getAllByText("dev-x")).toHaveLength(1);
  });

  it("the failure banner comes right after the header, before the state row, with no margin wrapper", async () => {
    const fake = await setup({ terminal: status({ enabled: false }) });
    fake.setReply("terminal_enable", () => {
      throw { kind: "cli-error", message: "disk full", exit_code: 1 };
    });
    await userEvent.click(await screen.findByRole("button", { name: "Turn on…" }));
    await userEvent.click(screen.getByRole("button", { name: "Turn on remote terminal" }));
    const alert = await screen.findByRole("alert");
    const header = document.querySelector(".view-header") as HTMLElement;
    expect(header.nextElementSibling).toBe(alert);
    expect(alert).toHaveClass("banner");
    expect(alert.parentElement?.classList.contains("banner-slot")).toBe(false);
  });
});

describe("Terminal view in Indonesian", () => {
  it("uses the ID catalog", async () => {
    const fake = fakeTransport({ snapshot: snapshotOf({ devices: [pixel] }), start_intent: { kind: "status" }, terminal_status: status({ enabled: false }) });
    renderWithApp(<TerminalView />, { client: createClient(fake.transport), locale: "id" });
    expect(await screen.findByRole("button", { name: /^Aktifkan/ })).toBeInTheDocument();
  });
});
