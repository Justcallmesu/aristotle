import { act, render, screen } from "@testing-library/react";
import { I18nProvider } from "../i18n";
import { createClient, IpcProvider, type StartIntent, type Transport } from "../ipc";
import { IntentProvider, useStartIntent } from "./intent";

function Probe() {
  const { pending } = useStartIntent();
  return <p data-testid="intent">{pending ? `${pending.intent.kind}#${pending.id}` : "none"}</p>;
}

function mount(transport: Transport) {
  render(
    <I18nProvider locale="en">
      <IpcProvider client={createClient(transport)}>
        <IntentProvider>
          <Probe />
        </IntentProvider>
      </IpcProvider>
    </I18nProvider>,
  );
}

describe("IntentProvider", () => {
  it("registers the start-intent listener before it reads the start intent", async () => {
    const order: string[] = [];
    let ready = false;
    const transport: Transport = {
      listen: async (event) => {
        order.push(`listen:${event}`);
        await new Promise((resolve) => setTimeout(resolve, 15));
        ready = true;
        return () => undefined;
      },
      invoke: async (command) => {
        if (command === "start_intent") {
          order.push(`read:${command}:${ready ? "after-listener" : "before-listener"}`);
          return { kind: "pair" } satisfies StartIntent;
        }
        return null;
      },
    };
    mount(transport);
    expect(await screen.findByText("pair#1")).toBeInTheDocument();
    expect(order).toEqual(["listen:start-intent", "read:start_intent:after-listener"]);
  });

  it("shows a hand-off that arrives as an event, with a new id", async () => {
    let push: (intent: StartIntent) => void = () => undefined;
    const transport: Transport = {
      listen: async (_event, handler) => {
        push = handler as (intent: StartIntent) => void;
        return () => undefined;
      },
      invoke: async () => ({ kind: "status" }) satisfies StartIntent,
    };
    mount(transport);
    expect(await screen.findByTestId("intent")).toHaveTextContent("status#1");
    act(() => push({ kind: "confirm-stop" }));
    expect(screen.getByTestId("intent")).toHaveTextContent("confirm-stop#2");
  });
});
