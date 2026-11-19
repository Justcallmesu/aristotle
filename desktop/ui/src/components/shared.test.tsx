import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nProvider } from "../i18n";
import { createClient, type SnapshotDto, type Status } from "../ipc";
import { fakeTransport, renderWithApp, snapshotOf } from "../test/fakes";
import { gate } from "../test/views";
import { CommandFailed } from "./CommandFailed";
import { NeedsServer } from "./NeedsServer";

function stopped(patch: Partial<Status> = {}, snapshot: Partial<SnapshotDto> = {}) {
  return snapshotOf({
    status: {
      kind: "answered",
      status: { service: "inactive", enabled: "enabled", installed: true, server: "not-running", result: null, ...patch },
    },
    ...snapshot,
  });
}

function renderNeeds(snapshot: SnapshotDto, replies: Record<string, unknown> = {}) {
  const fake = fakeTransport({ snapshot, start_intent: { kind: "status" }, ...replies });
  renderWithApp(<NeedsServer />, { client: createClient(fake.transport) });
  return fake;
}

describe("NeedsServer", () => {
  it("names the cause and offers Start when the service can be started", async () => {
    renderNeeds(stopped());
    expect(await screen.findByRole("heading", { name: "Needs the running server" })).toBeInTheDocument();
    expect(screen.getByText(/Start the server to read paired phones/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start server" })).toBeEnabled();
  });

  it("starts the service on click and shows the working hint, off while it runs", async () => {
    const fake = fakeTransport({ snapshot: stopped() });
    const held = gate(fake, "service_action");
    renderWithApp(<NeedsServer />, { client: createClient(fake.transport) });
    await userEvent.click(await screen.findByRole("button", { name: "Start server" }));
    expect(fake.calls.at(-1)).toEqual({ command: "service_action", args: { action: "start" } });
    expect(screen.getByRole("button", { name: "Start server" })).toBeDisabled();
    expect(screen.getByRole("progressbar", { name: "Starting…" })).toBeInTheDocument();
    await act(async () => held.resolve({ ok: true, service: "active" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Start server" })).toBeEnabled());
  });

  it("shows the failure banner when Start fails", async () => {
    const fake = fakeTransport({ snapshot: stopped() });
    gate(fake, "service_action").reject({ kind: "server-error", message: "unit is masked", exit_code: 1 });
    renderWithApp(<NeedsServer />, { client: createClient(fake.transport) });
    await userEvent.click(await screen.findByRole("button", { name: "Start server" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("error: unit is masked");
  });

  it.each(["active", "inactive", "failed", "activating", "deactivating", "reloading", "unknown", "maintenance"])(
    "service %s: Start shows only for inactive and failed (P21)",
    async (service) => {
      renderNeeds(stopped({ service }));
      await screen.findByRole("heading", { name: "Needs the running server" });
      const start = screen.queryByRole("button", { name: "Start server" });
      if (service === "inactive" || service === "failed") expect(start).toBeEnabled();
      else expect(start).toBeNull();
    },
  );

  it.each([
    ["not installed", stopped({ installed: false }), undefined],
    ["a version mismatch", stopped({}, { version: { kind: "mismatch", server: "1.0.0", desktop: "1.2.3" } }), undefined],
    ["the service already active", stopped({ service: "active", server: "no-answer" }), undefined],
  ])("has no Start button with %s", async (_name, snapshot) => {
    renderNeeds(snapshot);
    await screen.findByRole("heading", { name: "Needs the running server" });
    expect(screen.queryByRole("button", { name: "Start server" })).toBeNull();
  });
});

describe("CommandFailed", () => {
  function show(error: { kind: string; message: string | null; exit_code: number | null }, locale: "en" | "id" = "en") {
    render(
      <I18nProvider locale={locale}>
        <CommandFailed error={error} />
      </I18nProvider>,
    );
  }

  it("is an alert with the title, the body and the server text in a code block", () => {
    show({ kind: "server-error", message: "refused: inside a terminal", exit_code: 1 });
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("That did not work");
    expect(alert).toHaveTextContent("The command failed. The message from the server is below.");
    expect(alert.querySelector("pre.code")).toHaveTextContent("error: refused: inside a terminal");
  });

  it("has no outer margin wrapper: the banner is the alert itself", () => {
    show({ kind: "timeout", message: null, exit_code: null });
    const alert = screen.getByRole("alert");
    expect(alert).toHaveClass("banner");
    expect(document.querySelector(".banner-slot")).toBeNull();
  });

  it("a not-ok reply shows the raw service word as `service: <word>`, untranslated, in both languages", () => {
    const code = () => screen.getByRole("alert").querySelector("pre.code");
    for (const word of ["failed", "activating"]) {
      for (const locale of ["en", "id"] as const) {
        show({ kind: "not-ok", message: word, exit_code: null }, locale);
        expect(code()?.textContent).toBe(`service: ${word}`);
        cleanup();
      }
    }
  });

  it("a not-ok reply without a service word falls back to the error kind", () => {
    show({ kind: "not-ok", message: null, exit_code: null });
    expect(screen.getByRole("alert").querySelector("pre.code")?.textContent).toBe("error: not-ok");
  });

  it("falls back to the error kind when the server said nothing", () => {
    show({ kind: "timeout", message: null, exit_code: null });
    expect(screen.getByRole("alert").querySelector("pre.code")).toHaveTextContent("error: timeout");
  });
});
