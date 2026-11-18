import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { useState } from "react";
import { App } from "../App";
import { I18nProvider } from "../i18n";
import { createClient, IpcProvider } from "../ipc";
import { fakeTransport, renderWithApp, snapshotOf } from "../test/fakes";
import { ActionButton } from "./Button";
import { ConfirmDialog } from "./ConfirmDialog";

function renderApp(snapshot = snapshotOf(), intent: unknown = { kind: "status" }) {
  const fake = fakeTransport({ snapshot, start_intent: intent, terminal_status: { enabled: true } });
  const client = createClient(fake.transport);
  render(
    <I18nProvider locale="en">
      <IpcProvider client={client}>
        <App />
      </IpcProvider>
    </I18nProvider>,
  );
  return fake;
}

describe("shell", () => {
  it("renders the 6 nav items in two groups", async () => {
    renderApp();
    const nav = await screen.findByRole("navigation", { name: "Views" });
    const items = within(nav).getAllByRole("button");
    expect(items.map((b) => b.textContent?.replace(/\d+|On|Off/g, "").trim())).toEqual([
      "Status", "Service", "Logs", "Pair", "Devices", "Terminal",
    ]);
    expect(within(nav).getByText("Server")).toBeInTheDocument();
    expect(within(nav).getByText("Phones")).toBeInTheDocument();
  });

  it("marks the open view with aria-current and switches views", async () => {
    renderApp();
    const nav = await screen.findByRole("navigation");
    expect(within(nav).getByRole("button", { name: "Status" })).toHaveAttribute("aria-current", "page");
    await userEvent.click(within(nav).getByRole("button", { name: "Logs" }));
    expect(within(nav).getByRole("button", { name: "Logs" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("heading", { level: 1, name: "Logs" })).toBeInTheDocument();
  });

  it("shows the live state in the footer", async () => {
    renderApp();
    const footer = await screen.findByRole("status");
    await waitFor(() => expect(footer).toHaveTextContent("Server running"));
    expect(footer).toHaveTextContent("1.2.3 · Hermes reachable");
  });

  it("updates the footer from a snapshot event", async () => {
    const fake = renderApp();
    await screen.findByText("Server running");
    act(() =>
      fake.emit("snapshot", snapshotOf({ status: { kind: "failed" } })),
    );
    expect(await screen.findByText("Server not answering")).toBeInTheDocument();
  });

  it("shows the nav counts while running", async () => {
    renderApp(snapshotOf({ devices: [{ id: "a", name: "A", created_at: "", last_seen_at: "" }] }));
    const nav = await screen.findByRole("navigation");
    await waitFor(() => expect(within(nav).getByRole("button", { name: /Devices/ })).toHaveTextContent("1"));
    await waitFor(() => expect(within(nav).getByRole("button", { name: /Terminal/ })).toHaveTextContent("On"));
  });

  it("shows the not-installed state with no install button", async () => {
    renderApp(
      snapshotOf({
        status: {
          kind: "answered",
          status: { service: "inactive", enabled: "enabled", installed: false, server: "not-running", result: null },
        },
      }),
    );
    expect(await screen.findByRole("heading", { level: 1, name: /Service not installed/ })).toBeInTheDocument();
    expect(screen.getByText("./install.sh")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /install/i })).toBeNull();
  });

  it("shows the no-CLI state", async () => {
    renderApp(snapshotOf({ status: { kind: "cli-missing" } }));
    expect(await screen.findByRole("heading", { level: 1, name: "aristotle-server not found" })).toBeInTheDocument();
  });

  it("opens the Pair view for a --pair hand-off and the Service view for --confirm stop", async () => {
    const fake = renderApp(snapshotOf(), { kind: "pair" });
    expect(await screen.findByRole("heading", { level: 1, name: "Pair a phone" })).toBeInTheDocument();
    act(() => fake.emit("start-intent", { kind: "confirm-stop" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Service" })).toBeInTheDocument();
  });
});

describe("version mismatch", () => {
  const mismatch = snapshotOf({ version: { kind: "mismatch", server: "1.2.2", desktop: "1.2.3" } });

  it("shows the banner above the view", async () => {
    renderApp(mismatch);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Server and app don’t match");
    expect(alert).toHaveTextContent("The server is version 1.2.2, this app is version 1.2.3.");
  });

  it("disables action buttons but not plain ones", async () => {
    renderWithApp(
      <>
        <ActionButton>Do it</ActionButton>
        <button>Read it</button>
      </>,
      {
        client: createClient(fakeTransport({ snapshot: mismatch }).transport),
      },
    );
    await waitFor(() => expect(screen.getByRole("button", { name: "Do it" })).toBeDisabled());
    expect(screen.getByRole("button", { name: "Read it" })).toBeEnabled();
  });

  it("leaves action buttons on when the versions match", async () => {
    renderWithApp(<ActionButton>Do it</ActionButton>);
    await waitFor(() => expect(screen.getByRole("button", { name: "Do it" })).toBeEnabled());
  });
});

function Harness({ onConfirm, onCancel }: { onConfirm: () => void; onCancel?: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <I18nProvider locale="en">
      <button onClick={() => setOpen(true)}>Opener</button>
      {open && (
        <ConfirmDialog
          title="Stop the server?"
          confirmLabel="Stop server"
          onConfirm={() => {
            onConfirm();
            setOpen(false);
          }}
          onCancel={() => {
            onCancel?.();
            setOpen(false);
          }}
        >
          <p>Phones disconnect.</p>
        </ConfirmDialog>
      )}
    </I18nProvider>
  );
}

describe("confirm dialog", () => {
  it("is an alertdialog named by its title, with focus on Cancel", async () => {
    render(<Harness onConfirm={() => undefined} />);
    await userEvent.click(screen.getByText("Opener"));
    const dialog = screen.getByRole("alertdialog", { name: "Stop the server?" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
  });

  it("closes on Escape without confirming and returns focus to the opener", async () => {
    const onConfirm = vi.fn();
    render(<Harness onConfirm={onConfirm} />);
    const opener = screen.getByText("Opener");
    opener.focus();
    await userEvent.click(opener);
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(onConfirm).not.toHaveBeenCalled();
    expect(opener).toHaveFocus();
  });

  it("keeps Tab and Shift+Tab inside", async () => {
    render(<Harness onConfirm={() => undefined} />);
    await userEvent.click(screen.getByText("Opener"));
    const cancel = screen.getByRole("button", { name: "Cancel" });
    const confirm = screen.getByRole("button", { name: "Stop server" });
    await userEvent.tab();
    expect(confirm).toHaveFocus();
    await userEvent.tab();
    expect(cancel).toHaveFocus();
    await userEvent.tab({ shift: true });
    expect(confirm).toHaveFocus();
  });

  it("confirms once and cancels on the scrim", async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(<Harness onConfirm={onConfirm} onCancel={onCancel} />);
    await userEvent.click(screen.getByText("Opener"));
    await userEvent.click(screen.getByRole("button", { name: "Stop server" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByText("Opener"));
    fireEvent.click(document.querySelector(".scrim")!);
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });
});

describe("styles", () => {
  it("keep hex colours in tokens.css only", () => {
    const dir = join(__dirname, "../styles");
    for (const name of readdirSync(dir).filter((n) => n.endsWith(".css") && n !== "tokens.css")) {
      expect(readFileSync(join(dir, name), "utf8"), name).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    }
  });
});
