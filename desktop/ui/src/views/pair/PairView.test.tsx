import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createClient, type Pair, type SnapshotDto } from "../../ipc";
import { IntentProvider } from "../../state/intent";
import { fakeTransport, renderWithApp, snapshotOf } from "../../test/fakes";
import { PairView } from "./PairView";

const T0 = Date.parse("2026-11-18T10:00:00Z");
const MODULES = ["1110111", "1010101", "1110111", "0000000", "1111111", "1000001", "1111111"];

function pairOf(patch: Record<string, unknown> = {}): Pair {
  return {
    code: "K7Q2M9XD",
    expires_at: "2026-11-18T10:05:00Z",
    fingerprint: "7E54 D87B 0C1F 9A42 6B3E 11D0 8F5C 2A97 4E06 B1C8 D3F2 5A69 0E7B C4D1 BCA7 EAA7",
    addresses: [
      { url: "https://192.168.1.5:8760", kind: "lan", iface: "wlan0", default_route: true },
      { url: "https://100.74.61.96:8760", kind: "tailscale", iface: "tailscale0", default_route: false },
    ],
    qr_payload: "aristotle://pair?v=1&c=K7Q2M9XD",
    qr_error: null,
    qr_modules: MODULES,
    ...patch,
  } as Pair;
}

function stopped(): SnapshotDto {
  return snapshotOf({
    status: {
      kind: "answered",
      status: { service: "inactive", enabled: "enabled", installed: true, server: "not-running", result: null },
    },
  });
}

function setup(replies: Record<string, unknown> = {}, snapshot: SnapshotDto = snapshotOf(), intent = "status") {
  const fake = fakeTransport({ snapshot, start_intent: { kind: intent }, pair: pairOf(), ...replies });
  renderWithApp(
    <IntentProvider>
      <PairView />
    </IntentProvider>,
    { client: createClient(fake.transport) },
  );
  const pairCalls = () => fake.calls.filter((c) => c.command === "pair");
  return { ...fake, pairCalls };
}

const user = () => userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setInterval", "clearInterval", "Date"] });
  vi.setSystemTime(T0);
});
afterEach(() => vi.useRealTimers());

async function showCode(u: ReturnType<typeof user>) {
  await u.click(await screen.findByRole("button", { name: "Show pairing code" }));
  return screen.findByText("K7Q2 M9XD");
}

describe("Pair: before a code", () => {
  it("opening the view shows the button and issues nothing", async () => {
    const { pairCalls } = setup();
    expect(await screen.findByRole("button", { name: "Show pairing code" })).toBeEnabled();
    expect(screen.getByRole("heading", { level: 1, name: "Pair a phone" })).toBeInTheDocument();
    expect(screen.getByLabelText("Phone name (optional)")).toBeInTheDocument();
    expect(pairCalls()).toHaveLength(0);
  });

  it("sends the optional name, trimmed, or none", async () => {
    const u = user();
    const { pairCalls } = setup();
    await u.type(await screen.findByLabelText("Phone name (optional)"), "  pixel ");
    await u.click(screen.getByRole("button", { name: "Show pairing code" }));
    await screen.findByText("K7Q2 M9XD");
    expect(pairCalls()[0].args).toEqual({ name: "pixel" });
  });

  it("a name starting with - shows the error and keeps the button off", async () => {
    const u = user();
    const { pairCalls } = setup();
    await u.type(await screen.findByLabelText("Phone name (optional)"), "-x");
    expect(screen.getByText("A name can’t start with “-”.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Show pairing code" })).toBeDisabled();
    expect(pairCalls()).toHaveLength(0);
  });

  it("shows the loading word before the first poll", () => {
    const fake = fakeTransport({ snapshot: () => new Promise(() => undefined) });
    renderWithApp(<PairView />, { client: createClient(fake.transport) });
    expect(screen.getByText("Checking the server…")).toBeInTheDocument();
  });
});

describe("Pair: making a code", () => {
  it("shows the busy label and turns the button off until the reply", async () => {
    const u = user();
    let finish: (p: Pair) => void = () => undefined;
    const fake = fakeTransport({
      snapshot: snapshotOf(),
      start_intent: { kind: "status" },
      pair: () => new Promise((resolve) => (finish = resolve as (p: Pair) => void)),
    });
    renderWithApp(<PairView />, { client: createClient(fake.transport) });
    await u.click(await screen.findByRole("button", { name: "Show pairing code" }));
    const busy = await screen.findByRole("button", { name: "Making a code…" });
    expect(busy).toBeDisabled();
    await act(async () => finish(pairOf()));
    expect(await screen.findByText("K7Q2 M9XD")).toBeInTheDocument();
  });
});

describe("Pair: code", () => {
  it("shows QR rects, code, countdown, fingerprint, addresses", async () => {
    const u = user();
    setup();
    await showCode(u);
    const qr = screen.getByRole("img", { name: "Pairing QR code" });
    const svg = qr.querySelector("svg")!;
    const ones = MODULES.join("").split("").filter((c) => c === "1").length;
    expect(svg.querySelectorAll("rect")).toHaveLength(ones);
    expect(svg.getAttribute("viewBox")).toBe("0 0 15 15"); // 7 modules + 4 quiet zone each side
    expect(svg.querySelector("rect")!.getAttribute("x")).toBe("4");
    expect(screen.getByText("Expires in 05:00")).toBeInTheDocument();
    expect(screen.getByText("7E54")).toBeInTheDocument();
    expect(screen.getByText("EAA7")).toBeInTheDocument();
    expect(screen.getByText("https://192.168.1.5:8760")).toBeInTheDocument();
    expect(screen.getByText("LAN · wlan0 · default route")).toBeInTheDocument();
    expect(screen.getByText("Tailscale · tailscale0")).toBeInTheDocument();
    expect(screen.getByText("K7Q2 M9XD")).toHaveAttribute("data-sensitive");
    expect(screen.getByRole("button", { name: "Make a new code" })).toBeEnabled();
  });

  it("copies the raw 8-character code", async () => {
    const u = user();
    setup();
    await showCode(u);
    const write = vi.spyOn(navigator.clipboard, "writeText").mockResolvedValue();
    await u.click(screen.getByRole("button", { name: "Copy" }));
    expect(write).toHaveBeenCalledWith("K7Q2M9XD");
  });

  it("ticks each second with a determinate bar and announces only at minute boundaries", async () => {
    const u = user();
    setup();
    await showCode(u);
    const bar = screen.getByRole("progressbar");
    expect(bar).toHaveAttribute("aria-valuemax", "300");
    expect(bar).toHaveAttribute("aria-valuenow", "300");
    const polite = Array.from(document.querySelectorAll("[aria-live='polite']")).find((el) => el.classList.contains("sr-only"))!;
    expect(polite).toHaveTextContent("Expires in 5 min");
    await act(async () => vi.advanceTimersByTime(1000));
    expect(screen.getByText("Expires in 04:59")).toBeInTheDocument();
    expect(bar).toHaveAttribute("aria-valuenow", "299");
    expect(polite).toHaveTextContent("Expires in 5 min"); // 299 s is still 5 min
    await act(async () => vi.advanceTimersByTime(59_000));
    expect(screen.getByText("Expires in 04:00")).toBeInTheDocument();
    expect(polite).toHaveTextContent("Expires in 4 min");
  });

  it("Make a new code issues another code", async () => {
    const u = user();
    const { pairCalls } = setup();
    await showCode(u);
    await u.click(screen.getByRole("button", { name: "Make a new code" }));
    await waitFor(() => expect(pairCalls()).toHaveLength(2));
  });

  it("draws no QR tile when qr_modules is absent but still shows the code", async () => {
    const u = user();
    setup({ pair: pairOf({ qr_modules: undefined }) });
    await showCode(u);
    expect(screen.queryByRole("img", { name: "Pairing QR code" })).not.toBeInTheDocument();
    expect(screen.getByText("Expires in 05:00")).toBeInTheDocument();
  });
});

describe("Pair: expired (P11)", () => {
  it("drops code, QR and fingerprint from the DOM at expires_at and offers a new code", async () => {
    const u = user();
    setup();
    await showCode(u);
    await act(async () => vi.advanceTimersByTime(300_000));
    expect(screen.getByText("Code expired")).toBeInTheDocument();
    expect(screen.getByText(/It expired at \d\d:\d\d\. Make a new code to pair\./)).toBeInTheDocument();
    expect(screen.queryByText("K7Q2 M9XD")).not.toBeInTheDocument();
    expect(screen.queryByText("7E54")).not.toBeInTheDocument();
    expect(screen.queryByRole("img", { name: "Pairing QR code" })).not.toBeInTheDocument();
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
    expect(document.body.innerHTML).not.toContain("K7Q2");
    expect(document.body.innerHTML).not.toContain("aristotle://");
    expect(screen.getByText("Addresses in the QR")).toBeInTheDocument();
    expect(screen.getByText("https://192.168.1.5:8760")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Make a new code" })).toBeEnabled();
  });

  it("an expires_at already in the past is expired at once", async () => {
    const u = user();
    setup({ pair: pairOf({ expires_at: "2026-11-18T09:00:00Z" }) });
    await u.click(await screen.findByRole("button", { name: "Show pairing code" }));
    expect(await screen.findByText("Code expired")).toBeInTheDocument();
    expect(screen.queryByText("K7Q2 M9XD")).not.toBeInTheDocument();
  });
});

describe("Pair: no listener and failure", () => {
  it("no listener: dead tile, explanation, qr_error and Try again, no code", async () => {
    const u = user();
    const { pairCalls } = setup({
      pair: pairOf({ code: "K7Q2M9XD", qr_payload: "", qr_error: "no address to pair on", qr_modules: undefined, addresses: [] }),
    });
    await u.click(await screen.findByRole("button", { name: "Show pairing code" }));
    expect(await screen.findByText("No address to pair on")).toBeInTheDocument();
    expect(screen.getByText(/has no network listener up/)).toBeInTheDocument();
    expect(screen.getByText("qr_error: no address to pair on")).toBeInTheDocument();
    expect(screen.queryByText("K7Q2 M9XD")).not.toBeInTheDocument();
    expect(document.body.innerHTML).not.toContain("K7Q2");
    await u.click(screen.getByRole("button", { name: "Try again" }));
    await waitFor(() => expect(pairCalls()).toHaveLength(2));
  });

  it("an encode failure with a payload keeps the code and puts qr_error under Details (no no-listener state)", async () => {
    const u = user();
    setup({ pair: pairOf({ qr_error: "encode failed", qr_modules: undefined }) });
    await showCode(u);
    expect(screen.queryByText("No address to pair on")).not.toBeInTheDocument();
    expect(screen.getByText("qr_error: encode failed")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Details" })).toBeInTheDocument();
    expect(document.querySelector(".qr-tile")).toBeNull();
  });

  it("a failed command shows CommandFailed and keeps the form", async () => {
    const u = user();
    const fake = fakeTransport({
      snapshot: snapshotOf(),
      start_intent: { kind: "status" },
      pair: () => Promise.reject({ kind: "cli", message: "boom", exit_code: 1 }),
    });
    renderWithApp(<PairView />, { client: createClient(fake.transport) });
    await u.click(await screen.findByRole("button", { name: "Show pairing code" }));
    const alert = await screen.findByRole("alert");
    expect(within(alert).getByText("error: boom")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Show pairing code" })).toBeEnabled();
    // One placement (design 4.2): right after the header, no margin wrapper.
    expect((document.querySelector(".view-header") as HTMLElement).nextElementSibling).toBe(alert);
    expect(alert.parentElement?.classList.contains("banner-slot")).toBe(false);
  });
});

describe("Pair: needs server, mismatch, intent, unmount", () => {
  it("needs-server state when the server is not running, with Start offered", async () => {
    const { pairCalls } = setup({}, stopped());
    expect(await screen.findByRole("heading", { name: "Needs the running server" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start server" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "Show pairing code" })).not.toBeInTheDocument();
    expect(pairCalls()).toHaveLength(0);
  });

  it("mismatch: the button is off and the reason is written", async () => {
    setup({}, snapshotOf({ version: { kind: "mismatch", server: "1.0.0", desktop: "1.2.3" } }));
    expect(await screen.findByRole("button", { name: "Show pairing code" })).toBeDisabled();
    expect(screen.getByText("Actions are off until the server is updated.")).toBeInTheDocument();
  });

  it("the --pair start intent issues a code at once", async () => {
    const { pairCalls } = setup({}, snapshotOf(), "pair");
    expect(await screen.findByText("K7Q2 M9XD")).toBeInTheDocument();
    expect(pairCalls()).toHaveLength(1);
  });

  it("the --pair intent under a mismatch issues nothing", async () => {
    const { pairCalls } = setup({}, snapshotOf({ version: { kind: "mismatch", server: "1.0.0", desktop: "1.2.3" } }), "pair");
    await screen.findByRole("button", { name: "Show pairing code" });
    await act(async () => vi.advanceTimersByTime(10));
    expect(pairCalls()).toHaveLength(0);
  });

  it("a --pair hand-off that arrives while the server is down is dropped: starting it later issues no code", async () => {
    const { pairCalls, emit } = setup({}, stopped(), "pair");
    await screen.findByRole("heading", { name: "Needs the running server" });
    await act(async () => vi.advanceTimersByTime(10));
    act(() => emit("snapshot", snapshotOf({ seq: 5 })));
    await screen.findByRole("button", { name: "Show pairing code" });
    await act(async () => vi.advanceTimersByTime(10));
    expect(pairCalls()).toHaveLength(0);
  });

  it("unmounting drops the code: a fresh mount shows the empty form and no code text", async () => {
    const u = user();
    const fake = fakeTransport({ snapshot: snapshotOf(), start_intent: { kind: "status" }, pair: pairOf() });
    const client = createClient(fake.transport);
    const first = renderWithApp(<PairView />, { client });
    await showCode(u);
    first.unmount();
    expect(document.body.innerHTML).not.toContain("K7Q2");
    renderWithApp(<PairView />, { client });
    expect(await screen.findByRole("button", { name: "Show pairing code" })).toBeInTheDocument();
    expect(document.body.innerHTML).not.toContain("K7Q2");
    expect(document.body.innerHTML).not.toContain("aristotle://");
  });

  it("never logs to the console", async () => {
    const u = user();
    const spies = (["log", "info", "warn", "error", "debug"] as const).map((m) => vi.spyOn(console, m));
    setup();
    await showCode(u);
    await act(async () => vi.advanceTimersByTime(300_000));
    spies.forEach((s) => expect(s).not.toHaveBeenCalled());
    spies.forEach((s) => s.mockRestore());
  });
});
