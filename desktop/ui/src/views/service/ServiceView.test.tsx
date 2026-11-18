import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { snapshotOf } from "../../test/fakes";
import { called, gate, renderView, stoppedSnapshot, withStatus } from "../status/testing";
import { serviceButtons } from "./serviceButtons";
import { ServiceView } from "./ServiceView";
import { deriveWorld } from "../../state/world";
import { statusOf } from "../status/facts";

const btn = (name: string) => screen.getByRole("button", { name });
const states = () => ({
  start: btn("Start server").hasAttribute("disabled") ? "off" : "on",
  stop: btn("Stop server").hasAttribute("disabled") ? "off" : "on",
  restart: btn("Restart server").hasAttribute("disabled") ? "off" : "on",
});

async function open(snapshot: unknown, replies: Record<string, unknown> = {}) {
  const view = renderView(<ServiceView />, { snapshot, ...replies });
  await screen.findByRole("heading", { level: 1, name: "Service" });
  await waitFor(() => expect(screen.queryByText("Checking the server…")).toBeNull());
  return view;
}

describe("Service: which buttons are on (design.md 4.4)", () => {
  it("running: Stop and Restart on, Start off with the reason", async () => {
    await open(snapshotOf());
    expect(states()).toEqual({ start: "off", stop: "on", restart: "on" });
    expect(screen.getByText("Start is off: the server is already running.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Server running" })).toBeInTheDocument();
  });

  it.each(["inactive", "failed"])("service %s: Start on and primary, Stop and Restart off with the reason", async (service) => {
    await open(stoppedSnapshot({ service }));
    expect(states()).toEqual({ start: "on", stop: "off", restart: "off" });
    expect(btn("Start server")).toHaveClass("btn-primary");
    expect(screen.getByText("Stop and Restart are off: the server isn’t running.")).toBeInTheDocument();
  });

  it.each(["activating", "deactivating", "unknown"])("service %s: all off (the tray's rule)", async (service) => {
    await open(stoppedSnapshot({ service }));
    expect(states()).toEqual({ start: "off", stop: "off", restart: "off" });
  });

  it("running but service unknown (started by hand): all off", async () => {
    await open(withStatus({ service: "unknown" }));
    expect(states()).toEqual({ start: "off", stop: "off", restart: "off" });
  });

  it("server not answering with the service active: Stop and Restart stay on (recovery)", async () => {
    await open(withStatus({ server: "no-answer", result: null }));
    expect(screen.getByRole("heading", { name: "Server not answering" })).toBeInTheDocument();
    expect(states()).toEqual({ start: "off", stop: "on", restart: "on" });
  });

  it("not installed: all off with the reason", async () => {
    await open(stoppedSnapshot({ installed: false }));
    expect(states()).toEqual({ start: "off", stop: "off", restart: "off" });
    expect(screen.getByText("Nothing to control: the service isn’t installed.")).toBeInTheDocument();
  });

  it("version mismatch: all off with the reason", async () => {
    await open(snapshotOf({ version: { kind: "mismatch", server: "1.0.0", desktop: "1.2.3" } }));
    expect(states()).toEqual({ start: "off", stop: "off", restart: "off" });
    expect(screen.getByText("Actions are off until the server is updated.")).toBeInTheDocument();
  });

  it("no CLI: all off", async () => {
    await open(snapshotOf({ status: { kind: "cli-missing" } }));
    expect(states()).toEqual({ start: "off", stop: "off", restart: "off" });
  });

  it("loading: all off and the loading headline", async () => {
    renderView(<ServiceView />, {});
    expect(await screen.findByText("Checking the server…")).toBeInTheDocument();
    expect(states()).toEqual({ start: "off", stop: "off", restart: "off" });
  });

  it("serviceButtons agrees with the tray menu rule for every service word", () => {
    const rule = (service: string, server = "running") => {
      const snapshot = withStatus({ service, server, result: server === "running" ? snapshotOfResult() : null });
      return serviceButtons(statusOf(snapshot), deriveWorld(snapshot), false);
    };
    const snapshotOfResult = () => ({ version: "1", addresses: [], port: 1, listeners: null, hermes: null });
    expect(rule("active")).toMatchObject({ start: false, stop: true, restart: true });
    expect(rule("active", "no-answer")).toMatchObject({ stop: true, restart: true });
    for (const word of ["inactive", "failed"]) expect(rule(word, "not-running")).toMatchObject({ start: true, stop: false, restart: false });
    for (const word of ["activating", "reloading", "unknown", "maintenance"])
      expect(rule(word, "not-running")).toMatchObject({ start: false, stop: false, restart: false });
  });
});

describe("Service: Stop and Restart confirm (D15)", () => {
  it("Stop opens the dialog with the D15 text, focus on Cancel, and Cancel sends nothing", async () => {
    const { fake } = await open(snapshotOf());
    await userEvent.click(btn("Stop server"));
    const dialog = screen.getByRole("alertdialog", { name: "Stop the server?" });
    expect(dialog).toHaveTextContent("Phones disconnect and can’t reconnect until the server starts again.");
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(called(fake, "service_action")).toHaveLength(0);
    expect(btn("Stop server")).toHaveFocus();
  });

  it("Esc on the stop dialog sends nothing", async () => {
    const { fake } = await open(snapshotOf());
    await userEvent.click(btn("Stop server"));
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(called(fake, "service_action")).toHaveLength(0);
  });

  it("clicking the scrim on the stop dialog sends nothing", async () => {
    const { fake } = await open(snapshotOf());
    await userEvent.click(btn("Stop server"));
    await userEvent.click(document.querySelector(".scrim")!);
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(called(fake, "service_action")).toHaveLength(0);
  });

  it("confirming Stop runs stop once, shows working, all off, until the next poll", async () => {
    const { fake } = await open(snapshotOf());
    const held = gate(fake, "service_action");
    await userEvent.click(btn("Stop server"));
    await userEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Stop server" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(called(fake, "service_action").map((c) => c.args)).toEqual([{ action: "stop" }]);
    expect(screen.getByRole("progressbar", { name: "Stopping…" })).toBeInTheDocument();
    expect(states()).toEqual({ start: "off", stop: "off", restart: "off" });

    await act(async () => held.resolve({ ok: true, service: "inactive" }));
    expect(screen.getByRole("progressbar", { name: "Stopping…" })).toBeInTheDocument();
    act(() => fake.emit("snapshot", stoppedSnapshot({}, { seq: 2 })));
    await waitFor(() => expect(screen.queryByRole("progressbar")).toBeNull());
    expect(states()).toEqual({ start: "on", stop: "off", restart: "off" });
  });

  it("Restart: dialog text, Cancel sends nothing, confirm runs restart", async () => {
    const { fake } = await open(snapshotOf());
    await userEvent.click(btn("Restart server"));
    const dialog = screen.getByRole("alertdialog", { name: "Restart the server?" });
    expect(dialog).toHaveTextContent("Phones disconnect for a moment and reconnect on their own.");
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(called(fake, "service_action")).toHaveLength(0);

    gate(fake, "service_action");
    await userEvent.click(btn("Restart server"));
    const confirm = document.querySelector(".dialog .btn-primary") as HTMLElement;
    await userEvent.click(confirm);
    expect(called(fake, "service_action").map((c) => c.args)).toEqual([{ action: "restart" }]);
    expect(screen.getByRole("progressbar", { name: "Restarting…" })).toBeInTheDocument();
  });

  it("Esc on the restart dialog sends nothing", async () => {
    const { fake } = await open(snapshotOf());
    await userEvent.click(btn("Restart server"));
    await userEvent.keyboard("{Escape}");
    expect(called(fake, "service_action")).toHaveLength(0);
  });
});

describe("Service: Start and failures", () => {
  it("Start runs at once with no dialog and shows Starting…", async () => {
    const { fake } = await open(stoppedSnapshot());
    gate(fake, "service_action");
    await userEvent.click(btn("Start server"));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(called(fake, "service_action").map((c) => c.args)).toEqual([{ action: "start" }]);
    expect(screen.getByRole("progressbar", { name: "Starting…" })).toBeInTheDocument();
    expect(states()).toEqual({ start: "off", stop: "off", restart: "off" });
  });

  it("a failed command shows the banner with the server's message and re-enables the buttons", async () => {
    const { fake } = await open(snapshotOf());
    gate(fake, "service_action").reject({ kind: "refused", message: "refused: inside a remote terminal", exit_code: 1 });
    await userEvent.click(btn("Restart server"));
    await userEvent.click(document.querySelector(".dialog .btn-primary") as HTMLElement);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("That did not work");
    expect(alert).toHaveTextContent("error: refused: inside a remote terminal");
    expect(states()).toEqual({ start: "off", stop: "on", restart: "on" });
  });

  it("a resolved { ok: false } is a failure too", async () => {
    const { fake } = await open(snapshotOf());
    gate(fake, "service_action").resolve({ ok: false, service: "active" });
    await userEvent.click(btn("Stop server"));
    await userEvent.click(document.querySelector(".dialog .btn-danger") as HTMLElement);
    expect(await screen.findByRole("alert")).toHaveTextContent("The command failed.");
  });
});

describe("Service: state rows and Indonesian", () => {
  it("lists the unit, the service word and starts at login", async () => {
    await open(snapshotOf());
    expect(screen.getByText("aristotle-server.service · systemd --user")).toHaveClass("mono");
    expect(screen.getByText("active", { selector: ".kv-val" })).toBeInTheDocument();
    expect(screen.getByText("Yes", { selector: ".kv-val" })).toBeInTheDocument();
  });

  it("renders in Indonesian", async () => {
    renderView(<ServiceView />, { snapshot: stoppedSnapshot() }, { locale: "id" });
    expect(await screen.findByRole("button", { name: "Nyalakan server" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Hentikan server" })).toBeDisabled();
    expect(screen.getByText("Tombol Hentikan dan Mulai ulang mati: server tidak berjalan.")).toBeInTheDocument();
  });
});

describe("Service: --confirm stop start intent (P14)", () => {
  it("opens the stop dialog once the first poll says Stop is available", async () => {
    const { fake } = renderView(<ServiceView />, { snapshot: snapshotOf(), start_intent: { kind: "confirm-stop" } });
    expect(await screen.findByRole("alertdialog", { name: "Stop the server?" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(called(fake, "service_action")).toHaveLength(0);
  });

  it("does not reopen the dialog after Cancel on the next poll", async () => {
    const { fake } = renderView(<ServiceView />, { snapshot: snapshotOf(), start_intent: { kind: "confirm-stop" } });
    await userEvent.click(await screen.findByRole("button", { name: "Cancel" }));
    act(() => fake.emit("snapshot", snapshotOf({ seq: 2 })));
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });

  it("confirming from the intent dialog runs stop", async () => {
    const { fake } = renderView(<ServiceView />, { snapshot: snapshotOf(), start_intent: { kind: "confirm-stop" } });
    gate(fake, "service_action");
    await screen.findByRole("alertdialog");
    await userEvent.click(document.querySelector(".dialog .btn-danger") as HTMLElement);
    expect(called(fake, "service_action").map((c) => c.args)).toEqual([{ action: "stop" }]);
  });

  it("opens no dialog when the server is already stopped", async () => {
    renderView(<ServiceView />, { snapshot: stoppedSnapshot(), start_intent: { kind: "confirm-stop" } });
    await screen.findByRole("heading", { name: "Server stopped" });
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });

  it("a plain status intent opens no dialog", async () => {
    renderView(<ServiceView />, { snapshot: snapshotOf(), start_intent: { kind: "status" } });
    await screen.findByRole("heading", { name: "Server running" });
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });
});
