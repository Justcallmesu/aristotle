import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useRoute } from "../../state/route";
import { snapshotOf } from "../../test/fakes";
import { called, renderView, stoppedSnapshot } from "../status/testing";
import { appendLines, EMPTY_BUFFER, LOG_CAP } from "./logBuffer";
import { parseLine } from "./logLine";
import { LogsView } from "./LogsView";

const REPLIES = { logs_start: null, logs_stop: null };
const batch = (from: number, to: number) => Array.from({ length: to - from }, (_, i) => `line ${from + i}`);

async function open(snapshot = snapshotOf(), replies: Record<string, unknown> = {}) {
  const view = renderView(<LogsView />, { snapshot, ...REPLIES, ...replies });
  await screen.findByRole("heading", { level: 1, name: "Logs" });
  await waitFor(() => expect(called(view.fake, "logs_start")).toHaveLength(1));
  return view;
}

const box = () => screen.getByRole("log");
const lines = () => Array.from(box().querySelectorAll(".log-line"));

/** jsdom has no layout: give the log box a scroll geometry and scroll it. */
function scrollTo(top: number, height = 1000, client = 200) {
  const el = box();
  Object.defineProperty(el, "scrollHeight", { configurable: true, value: height });
  Object.defineProperty(el, "clientHeight", { configurable: true, value: client });
  Object.defineProperty(el, "scrollTop", { configurable: true, writable: true, value: top });
  fireEvent.scroll(el);
}

describe("log buffer", () => {
  it("keeps the newest 2,000 lines and drops the oldest", () => {
    let buffer = appendLines(EMPTY_BUFFER, batch(0, 1500));
    buffer = appendLines(buffer, batch(1500, 2600));
    expect(buffer.entries).toHaveLength(LOG_CAP);
    expect(buffer.entries[0].text).toBe("line 600");
    expect(buffer.entries.at(-1)!.text).toBe("line 2599");
    expect(new Set(buffer.entries.map((e) => e.id)).size).toBe(LOG_CAP);
  });

  it("an empty batch changes nothing and one oversized batch is trimmed", () => {
    expect(appendLines(EMPTY_BUFFER, [])).toBe(EMPTY_BUFFER);
    expect(appendLines(EMPTY_BUFFER, batch(0, 2500)).entries[0].text).toBe("line 500");
  });
});

describe("parseLine", () => {
  const journal = "Nov 18 20:31:02 pc aristotle-server[1234]: level=ERROR msg=boom";
  it("splits the journal prefix and reads the level", () => {
    expect(parseLine(journal)).toEqual({ prefix: "Nov 18 20:31:02 pc aristotle-server[1234]:", rest: " level=ERROR msg=boom", level: "error" });
    expect(parseLine("Nov  3 01:02:03 pc aristotle-server[9]: level=WARN x").level).toBe("warn");
    expect(parseLine("Nov 18 20:31:02 pc aristotle-server[1]: level=INFO ok").level).toBeNull();
  });
  it("leaves any other line whole", () => {
    expect(parseLine("-- Journal begins")).toEqual({ prefix: "", rest: "-- Journal begins", level: null });
  });
});

describe("Logs: stream lifecycle", () => {
  it("calls logs_start on open, after subscribing to the line events", async () => {
    const { fake } = await open();
    expect(called(fake, "logs_start")).toHaveLength(1);
    act(() => fake.emit("logs-lines", { lines: ["first"] }));
    expect(await screen.findByText("first")).toBeInTheDocument();
  });

  it("calls logs_stop on unmount", async () => {
    const { fake } = await open();
    expect(called(fake, "logs_stop")).toHaveLength(0);
    cleanup();
    expect(called(fake, "logs_stop")).toHaveLength(1);
  });

  it("calls logs_stop when the user leaves the view", async () => {
    function Host() {
      const [view] = useRoute();
      return view === "logs" ? <LogsView /> : <p>elsewhere</p>;
    }
    window.location.hash = "#/logs";
    const { fake } = renderView(<Host />, { snapshot: snapshotOf(), ...REPLIES });
    await waitFor(() => expect(called(fake, "logs_start")).toHaveLength(1));
    act(() => {
      window.location.hash = "#/devices";
    });
    expect(await screen.findByText("elsewhere")).toBeInTheDocument();
    expect(called(fake, "logs_stop")).toHaveLength(1);
    // Nothing arrives after the view is gone.
    act(() => fake.emit("logs-lines", { lines: ["late"] }));
    expect(screen.queryByText("late")).toBeNull();
  });
});

describe("Logs: lines", () => {
  it("renders lines as text, never as HTML", async () => {
    const { fake } = await open();
    act(() => fake.emit("logs-lines", { lines: ["<script>window.hacked=1</script>", "<img src=x onerror=alert(1)> level=WARN"] }));
    expect(await screen.findByText("<script>window.hacked=1</script>")).toBeInTheDocument();
    expect(box().querySelector("script")).toBeNull();
    expect(box().querySelector("img")).toBeNull();
    expect(box().textContent).toContain("<img src=x onerror=alert(1)>");
    expect((window as unknown as { hacked?: number }).hacked).toBeUndefined();
  });

  it("marks ERROR and WARN rows and keeps the level word bold", async () => {
    const { fake } = await open();
    act(() =>
      fake.emit("logs-lines", {
        lines: [
          "Nov 18 20:31:02 pc aristotle-server[1]: level=ERROR msg=boom",
          "Nov 18 20:31:03 pc aristotle-server[1]: level=WARN msg=hmm",
          "Nov 18 20:31:04 pc aristotle-server[1]: level=INFO msg=ok",
        ],
      }),
    );
    await waitFor(() => expect(lines()).toHaveLength(3));
    expect(lines()[0]).toHaveClass("log-error");
    expect(lines()[1]).toHaveClass("log-warn");
    expect(lines()[2]).not.toHaveClass("log-error");
    expect(within(lines()[0] as HTMLElement).getByText("level=ERROR").tagName).toBe("STRONG");
    expect(lines()[0].querySelector(".log-prefix")).toHaveTextContent("Nov 18 20:31:02 pc aristotle-server[1]:");
  });

  it("caps the view at 2,000 lines and says so", async () => {
    const { fake } = await open();
    act(() => fake.emit("logs-lines", { lines: batch(0, 1200) }));
    act(() => fake.emit("logs-lines", { lines: batch(1200, 2500) }));
    await waitFor(() => expect(lines()).toHaveLength(2000));
    expect(lines()[0]).toHaveTextContent("line 500");
    expect(lines().at(-1)).toHaveTextContent("line 2499");
    expect(screen.getByText(/keeps the last 2,000 lines/)).toBeInTheDocument();
  });

  it("shows only the empty box before any line", async () => {
    await open();
    expect(lines()).toHaveLength(0);
    expect(box()).toHaveAttribute("aria-live", "off");
    expect(box()).toHaveAttribute("tabindex", "0");
  });
});

describe("Logs: wrap and follow", () => {
  it("wrap is on by default and the switch turns it off and on", async () => {
    await open();
    const wrap = screen.getByRole("switch", { name: "Wrap lines" });
    expect(wrap).toHaveAttribute("aria-checked", "true");
    expect(box()).not.toHaveClass("log-nowrap");
    await userEvent.click(wrap);
    expect(wrap).toHaveAttribute("aria-checked", "false");
    expect(box()).toHaveClass("log-nowrap");
    await userEvent.click(wrap);
    expect(box()).not.toHaveClass("log-nowrap");
  });

  it("follow is on by default with the Live word and no Jump button", async () => {
    await open();
    expect(screen.getByRole("switch", { name: "Follow" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByText("Live")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Jump to latest" })).toBeNull();
  });

  it("follow keeps the box scrolled to the newest line", async () => {
    const { fake } = await open();
    Object.defineProperty(box(), "scrollHeight", { configurable: true, value: 640 });
    act(() => fake.emit("logs-lines", { lines: batch(0, 5) }));
    await waitFor(() => expect(box().scrollTop).toBe(640));
  });

  it("scrolling up pauses, counts new lines, and Jump to latest resumes", async () => {
    const { fake } = await open();
    act(() => fake.emit("logs-lines", { lines: batch(0, 10) }));
    await waitFor(() => expect(lines()).toHaveLength(10));
    scrollTo(0);
    expect(await screen.findByText("Paused · 0 new lines")).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: "Follow" })).toHaveAttribute("aria-checked", "false");

    act(() => fake.emit("logs-lines", { lines: batch(10, 13) }));
    expect(await screen.findByText("Paused · 3 new lines")).toBeInTheDocument();
    act(() => fake.emit("logs-lines", { lines: batch(13, 15) }));
    expect(await screen.findByText("Paused · 5 new lines")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Jump to latest" }));
    expect(screen.getByText("Live")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Jump to latest" })).toBeNull();
    expect(screen.getByRole("switch", { name: "Follow" })).toHaveAttribute("aria-checked", "true");

    // The counter starts from zero on the next pause.
    scrollTo(0);
    expect(await screen.findByText("Paused · 0 new lines")).toBeInTheDocument();
  });

  it("flipping the Follow switch off pauses, and scrolling back to the bottom resumes", async () => {
    await open();
    await userEvent.click(screen.getByRole("switch", { name: "Follow" }));
    expect(screen.getByText("Paused · 0 new lines")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Jump to latest" })).toBeInTheDocument();
    scrollTo(800);
    expect(await screen.findByText("Live")).toBeInTheDocument();
  });
});

describe("Logs: server stopped", () => {
  it("disables Follow, shows no Live or Paused word, keeps the lines and writes the hint", async () => {
    const { fake } = await open(snapshotOf());
    act(() => fake.emit("logs-lines", { lines: ["last words"] }));
    await screen.findByText("last words");
    act(() => fake.emit("snapshot", stoppedSnapshot({}, { seq: 2 })));
    await waitFor(() => expect(screen.getByRole("switch", { name: "Follow" })).toHaveAttribute("aria-disabled", "true"));
    expect(screen.queryByText("Live")).toBeNull();
    expect(screen.queryByText(/Paused/)).toBeNull();
    expect(screen.getByText("last words")).toBeInTheDocument();
    expect(screen.getByText("The server is stopped. The journal ends at the last line above.")).toBeInTheDocument();
  });

  it("starts stopped: still opens the stream and shows the hint", async () => {
    await open(stoppedSnapshot());
    expect(screen.getByText(/The server is stopped/)).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: "Follow" })).toHaveAttribute("aria-disabled", "true");
  });
});

describe("Logs: stream ended or failed", () => {
  it("keeps the lines, shows an alert with Reconnect, and Reconnect starts again with fresh lines", async () => {
    const { fake } = await open();
    act(() => fake.emit("logs-lines", { lines: ["before the end"] }));
    await screen.findByText("before the end");
    act(() => fake.emit("logs-ended", null));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The log stream stopped.");
    expect(screen.getByText("before the end")).toBeInTheDocument();

    await userEvent.click(within(alert).getByRole("button", { name: "Reconnect" }));
    await waitFor(() => expect(called(fake, "logs_start")).toHaveLength(2));
    expect(called(fake, "logs_stop").length).toBeGreaterThanOrEqual(1);
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText("before the end")).toBeNull();
    act(() => fake.emit("logs-lines", { lines: ["after reconnect"] }));
    expect(await screen.findByText("after reconnect")).toBeInTheDocument();
  });

  it("a stream that fails to start shows the same alert", async () => {
    const view = renderView(<LogsView />, { snapshot: snapshotOf(), logs_stop: null });
    expect(await screen.findByRole("alert")).toHaveTextContent("The log stream stopped.");
    expect(screen.getByRole("button", { name: "Reconnect" })).toBeInTheDocument();
    expect(called(view.fake, "logs_start")).toHaveLength(1);
  });
});

describe("Logs: Indonesian", () => {
  it("renders the bar and notes in Indonesian", async () => {
    renderView(<LogsView />, { snapshot: snapshotOf(), ...REPLIES }, { locale: "id" });
    expect(await screen.findByRole("switch", { name: "Ikuti" })).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: "Bungkus baris" })).toBeInTheDocument();
    expect(screen.getByText("Langsung")).toBeInTheDocument();
    expect(screen.getByRole("log", { name: "Log" })).toBeInTheDocument();
  });
});
