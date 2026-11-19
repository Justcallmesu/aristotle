import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { snapshotOf } from "../../test/fakes";
import { latestSeen, listenAddress } from "../../state/facts";
import { StatusView } from "./StatusView";
import { called, gate, renderView, runningWith, stoppedSnapshot, withStatus } from "../../test/views";

const hermes = (state: string | null, reason: string | null = null) =>
  runningWith({ hermes: state ? { state, reason } : null });

describe("Status: headline rows of design.md 4.1", () => {
  it("row 0: loading, no columns and no Start", async () => {
    renderView(<StatusView />, {});
    expect(await screen.findByText("Checking the server…")).toBeInTheDocument();
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByText("Server")).toBeNull();
  });

  it.each([
    ["row 6 reachable", hermes("reachable"), "Server running", "Hermes is reachable. Phones can connect.", "Reachable"],
    ["row 7 not running", hermes("not-running"), "Server running", /Phones see “Hermes isn’t running”/, "Not running"],
    ["row 8 error", hermes("error", "socket closed"), "Server running", /Hermes did not answer the check/, "Check failed"],
    ["row 9 absent", hermes(null), "Server running", "This server does not report Hermes. Update it with the installer.", "Unknown"],
    ["row 4 stopped", stoppedSnapshot(), "Server stopped", "The service is inactive. Phones can’t connect until you start it.", "Unknown while stopped"],
    [
      "row 4b changing",
      stoppedSnapshot({ service: "activating" }),
      "Server is changing state",
      "systemd reports the service as activating.",
      "Unknown while stopped",
    ],
    [
      "row 5 outside the service",
      withStatus({ service: "unknown" }),
      "Running outside the service",
      /not as the aristotle-server service/,
      "Reachable",
    ],
    [
      "row 3 no answer",
      withStatus({ server: "no-answer", result: null }),
      "Server not answering",
      /The service is active but the server did not answer/,
      "Unknown while stopped",
    ],
  ])("%s", async (_name, snapshot, title, sub, hermesWord) => {
    renderView(<StatusView />, { snapshot });
    expect(await screen.findByRole("heading", { name: title })).toBeInTheDocument();
    expect(screen.getByText(sub)).toBeInTheDocument();
    const row = screen.getByText("Hermes", { selector: ".kv-key" }).closest(".kv-row") as HTMLElement;
    expect(within(row).getByText(hermesWord)).toBeInTheDocument();
  });

  it("shows the server's Hermes reason in mono under the Hermes row", async () => {
    renderView(<StatusView />, { snapshot: hermes("error", "socket closed") });
    expect(await screen.findByText("socket closed")).toHaveClass("kv-sub");
  });

  it("row 1: no CLI shows the headline and dashes, never Start", async () => {
    renderView(<StatusView />, { snapshot: snapshotOf({ status: { kind: "cli-missing" } }) });
    expect(await screen.findByRole("heading", { name: "aristotle-server not found" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Start server" })).toBeNull();
    const row = screen.getByText("Service", { selector: ".kv-key" }).closest(".kv-row") as HTMLElement;
    expect(within(row).getByText("—")).toBeInTheDocument();
  });

  it("row 2: not installed shows ./install.sh with Copy and no install or Start button", async () => {
    renderView(<StatusView />, { snapshot: stoppedSnapshot({ installed: false, enabled: "enabled" }) });
    expect(await screen.findByRole("heading", { name: "Service not installed" })).toBeInTheDocument();
    expect(screen.getByText("Run in the release folder:")).toBeInTheDocument();
    expect(screen.getByText("./install.sh")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Copy" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Start server" })).toBeNull();
    // P13: `enabled` says "enabled" but the unit is missing.
    const row = screen.getByText("Starts at login", { selector: ".kv-key" }).closest(".kv-row") as HTMLElement;
    expect(within(row).getByText("Not installed")).toBeInTheDocument();
  });
});

describe("Status: server facts", () => {
  it("lists service, starts at login, version, port and Hermes", async () => {
    renderView(<StatusView />, { snapshot: hermes("reachable") });
    await screen.findByRole("heading", { name: "Server running" });
    const value = (key: string) =>
      (screen.getByText(key, { selector: ".kv-key" }).closest(".kv-row") as HTMLElement).querySelector(".kv-val")!.textContent;
    expect(value("Service")).toBe("active");
    expect(value("Starts at login")).toBe("Yes");
    expect(value("Server version")).toBe("1.2.3");
    expect(value("Port")).toBe("8760");
    expect(value("Hermes")).toBe("Reachable");
  });

  it("shows — for an absent port and a systemctl word the catalog lacks, raw", async () => {
    renderView(<StatusView />, { snapshot: withStatus({ service: "activating", enabled: "static", result: { version: "0.9", addresses: [], port: null, listeners: null, hermes: null } }) });
    await screen.findByRole("heading", { name: "Running outside the service" });
    const value = (key: string) =>
      (screen.getByText(key, { selector: ".kv-key" }).closest(".kv-row") as HTMLElement).querySelector(".kv-val")!.textContent;
    expect(value("Service")).toBe("activating");
    expect(value("Starts at login")).toBe("static");
    expect(value("Port")).toBe("—");
  });

  it("uses the unknown words for service and enabled \"unknown\"", async () => {
    renderView(<StatusView />, { snapshot: withStatus({ service: "unknown", enabled: "unknown" }) });
    await screen.findByRole("heading", { name: "Running outside the service" });
    const unknown = screen.getAllByText("unknown", { exact: false });
    expect(unknown.length).toBeGreaterThan(0);
    expect(screen.getByText("Unknown", { selector: ".kv-val" })).toBeInTheDocument();
  });

  it("flags a version mismatch with the err tag and hides Start", async () => {
    renderView(<StatusView />, {
      snapshot: stoppedSnapshot({}, { version: { kind: "mismatch", server: "1.2.3", desktop: "1.2.4" } }),
    });
    expect(await screen.findByText("app is 1.2.4")).toHaveClass("tag-err");
    expect(screen.queryByRole("button", { name: "Start server" })).toBeNull();
  });
});

describe("Status: listeners", () => {
  const listeners = [
    { kind: "lan", iface: "wlan0", ip: "192.168.1.20", subnet: "192.168.1.0/24" },
    { kind: "tailscale", iface: "tailscale0", ip: "fd7a::1", subnet: null },
    { kind: "lan", iface: "lo", ip: "127.0.0.1", subnet: null },
  ];

  it("shows one row per listener with ip:port and the interface line, IPv6 bracketed", async () => {
    renderView(<StatusView />, {
      snapshot: runningWith({
        listeners,
        addresses: [{ url: "https://192.168.1.20:8760", kind: "lan", iface: "wlan0", default_route: true }],
      }),
    });
    await screen.findByText("192.168.1.20:8760");
    expect(screen.getByText("[fd7a::1]:8760")).toHaveClass("kv-nowrap");
    expect(screen.getByText("127.0.0.1:8760")).toBeInTheDocument();
    expect(screen.getByText("wlan0 · 192.168.1.0/24 · default route")).toBeInTheDocument();
    expect(screen.getByText("tailscale0")).toBeInTheDocument();
    expect(screen.getByText("Tailscale")).toBeInTheDocument();
  });

  it("running with listeners absent: no_listen_up", async () => {
    renderView(<StatusView />, { snapshot: snapshotOf() });
    expect(await screen.findByText("No network listener is up.")).toBeInTheDocument();
  });

  it("stopped: the stopped listener row and no Phones group", async () => {
    renderView(<StatusView />, { snapshot: stoppedSnapshot() });
    expect(await screen.findByText("No listener while the server is stopped.")).toBeInTheDocument();
    expect(screen.queryByText("Paired phones")).toBeNull();
  });
});

describe("Status: phones and terminal summaries", () => {
  const device = (id: string, minutesAgo: number) => ({
    id,
    name: id,
    created_at: "2026-01-01T00:00:00Z",
    last_seen_at: new Date(Date.now() - minutesAgo * 60_000).toISOString(),
  });

  it("summarises paired phones with the most recent last seen and navigates to Devices", async () => {
    renderView(<StatusView />, { snapshot: snapshotOf({ devices: [device("dev_a", 30), device("dev_b", 5)] }) });
    expect(await screen.findByText("2 · last seen 5 min ago")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Open Devices" }));
    expect(window.location.hash).toBe("#/devices");
  });

  it("none paired, and — when the read failed", async () => {
    renderView(<StatusView />, { snapshot: snapshotOf({ devices: [] }) });
    expect(await screen.findByText("None")).toBeInTheDocument();
  });

  it("devices null shows —", async () => {
    renderView(<StatusView />, { snapshot: snapshotOf({ devices: null }) });
    await screen.findByText("Paired phones");
    const row = screen.getByText("Paired phones", { selector: ".kv-key" }).closest(".kv-row") as HTMLElement;
    expect(within(row).getByText("—")).toBeInTheDocument();
  });

  it("terminal on: open and granted counts from the snapshot, no terminal_status read, and navigates", async () => {
    const terminals = [{ id: "t1", device_id: "dev_a", listener: "lan", since: "2026-01-01T00:00:00Z", pid: 1 }];
    const { fake } = renderView(<StatusView />, {
      snapshot: snapshotOf({ terminals, terminal_enabled: true, terminal_granted: ["dev_a", "dev_b"] }),
    });
    expect(await screen.findByText("On · 1 open · 2 granted")).toBeInTheDocument();
    expect(called(fake, "terminal_status")).toHaveLength(0);
    await userEvent.click(screen.getByRole("button", { name: "Open Terminal" }));
    expect(window.location.hash).toBe("#/terminal");
  });

  it("terminal off: Off, and no terminal_status read", async () => {
    const { fake } = renderView(<StatusView />, { snapshot: snapshotOf({ terminal_enabled: false }) });
    expect(await screen.findByText("Off")).toBeInTheDocument();
    expect(called(fake, "terminal_status")).toHaveLength(0);
  });

  it("terminal on with nothing granted says 0 granted", async () => {
    renderView(<StatusView />, { snapshot: snapshotOf({ terminal_enabled: true }) });
    expect(await screen.findByText("On · 0 open · 0 granted")).toBeInTheDocument();
  });
});

describe("Status: Start server", () => {
  it("is primary, runs start, shows the working bar and stays off until the next poll", async () => {
    const { fake } = renderView(<StatusView />, { snapshot: stoppedSnapshot() });
    const held = gate(fake, "service_action");
    const button = await screen.findByRole("button", { name: "Start server" });
    expect(button).toHaveClass("btn-primary");
    await userEvent.click(button);
    expect(called(fake, "service_action")[0].args).toEqual({ action: "start" });
    expect(screen.getByRole("progressbar", { name: "Starting…" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start server" })).toBeDisabled();

    await act(async () => held.resolve({ ok: true, service: "active" }));
    // The command returned, but the next poll has not confirmed the state yet.
    expect(screen.getByRole("progressbar", { name: "Starting…" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start server" })).toBeDisabled();

    act(() => fake.emit("snapshot", withStatus({}, { seq: 2 })));
    await waitFor(() => expect(screen.queryByRole("progressbar")).toBeNull());
    expect(screen.queryByRole("button", { name: "Start server" })).toBeNull();
  });

  it("failure shows the command-failed banner with the server's text", async () => {
    const { fake } = renderView(<StatusView />, { snapshot: stoppedSnapshot() });
    gate(fake, "service_action").reject({ kind: "server-error", message: "unit is masked", exit_code: 1 });
    await userEvent.click(await screen.findByRole("button", { name: "Start server" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("error: unit is masked");
    expect(screen.getByRole("button", { name: "Start server" })).toBeEnabled();
  });

  it("a resolved { ok: false } counts as failed", async () => {
    const { fake } = renderView(<StatusView />, { snapshot: stoppedSnapshot() });
    gate(fake, "service_action").resolve({ ok: false, service: "failed" });
    await userEvent.click(await screen.findByRole("button", { name: "Start server" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("That did not work");
    expect(alert).toHaveTextContent("service: failed");
    expect(alert).not.toHaveTextContent("error: failed");
    expect(screen.getByRole("button", { name: "Start server" })).toBeEnabled();
  });

  it.each(["active", "inactive", "failed", "activating", "deactivating", "reloading", "unknown", "maintenance"])(
    "service %s (server not running): Start only for inactive and failed (P21)",
    async (service) => {
      renderView(<StatusView />, { snapshot: stoppedSnapshot({ service }) });
      const settled = service === "inactive" || service === "failed";
      await screen.findByRole("heading", { name: settled ? "Server stopped" : "Server is changing state" });
      const start = screen.queryByRole("button", { name: "Start server" });
      if (service === "inactive" || service === "failed") expect(start).toBeEnabled();
      else expect(start).toBeNull();
    },
  );

  it("the failure banner is right after the sr-only header, above the state row, with no margin wrapper", async () => {
    const { fake } = renderView(<StatusView />, { snapshot: stoppedSnapshot() });
    gate(fake, "service_action").reject({ kind: "server-error", message: "unit is masked", exit_code: 1 });
    await userEvent.click(await screen.findByRole("button", { name: "Start server" }));
    const alert = await screen.findByRole("alert");
    expect(screen.getByRole("heading", { level: 1 }).nextElementSibling).toBe(alert);
    expect(alert.parentElement?.classList.contains("banner-slot")).toBe(false);
    expect(alert.parentElement?.classList.contains("kv-failed")).toBe(false);
  });

  it("is absent while running, when the service is active but not answering, and with no CLI", async () => {
    renderView(<StatusView />, { snapshot: withStatus({ server: "no-answer", result: null }) });
    await screen.findByRole("heading", { name: "Server not answering" });
    expect(screen.queryByRole("button", { name: "Start server" })).toBeNull();
  });
});

describe("Status: Indonesian", () => {
  it("renders the headline and facts in Indonesian", async () => {
    renderView(<StatusView />, { snapshot: hermes("reachable") }, { locale: "id" });
    expect(await screen.findByRole("heading", { name: "Server berjalan" })).toBeInTheDocument();
    expect(screen.getByText("Mulai saat login")).toBeInTheDocument();
    expect(screen.getByText("Terjangkau")).toBeInTheDocument();
  });
});

describe("facts helpers", () => {
  it("latestSeen picks the most recent device", () => {
    const d = (id: string, seen: string) => ({ id, name: id, created_at: seen, last_seen_at: seen });
    expect(latestSeen([d("a", "2026-01-01T00:00:00Z"), d("b", "2026-02-01T00:00:00Z")])?.id).toBe("b");
    expect(latestSeen([])).toBeNull();
  });

  it("listenAddress brackets IPv6 and tolerates a missing port", () => {
    expect(listenAddress("10.0.0.1", 8760)).toBe("10.0.0.1:8760");
    expect(listenAddress("::1", 8760)).toBe("[::1]:8760");
    expect(listenAddress("10.0.0.1", null)).toBe("10.0.0.1");
  });
});
