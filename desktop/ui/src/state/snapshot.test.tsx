import { act, render, screen, waitFor } from "@testing-library/react";
import { I18nProvider } from "../i18n";
import { createClient, IpcProvider } from "../ipc";
import { fakeTransport, snapshotOf } from "../test/fakes";
import { SnapshotProvider, useSnapshot } from "./snapshot";

function Seq() {
  const { snapshot } = useSnapshot();
  return <p data-testid="seq">{snapshot ? snapshot.seq : "none"}</p>;
}

function setup(first = snapshotOf({ seq: 1 })) {
  const fake = fakeTransport({ snapshot: first });
  render(
    <I18nProvider locale="en">
      <IpcProvider client={createClient(fake.transport)}>
        <SnapshotProvider>
          <Seq />
        </SnapshotProvider>
      </IpcProvider>
    </I18nProvider>,
  );
  return fake;
}

describe("snapshot sequence numbers", () => {
  it("ignores an event older than the snapshot on screen", async () => {
    const fake = setup();
    await waitFor(() => expect(screen.getByTestId("seq")).toHaveTextContent("1"));
    act(() => fake.emit("snapshot", snapshotOf({ seq: 5 })));
    expect(screen.getByTestId("seq")).toHaveTextContent("5");
    act(() => fake.emit("snapshot", snapshotOf({ seq: 3 })));
    expect(screen.getByTestId("seq")).toHaveTextContent("5");
    act(() => fake.emit("snapshot", snapshotOf({ seq: 6 })));
    expect(screen.getByTestId("seq")).toHaveTextContent("6");
  });

  it("does not let the first read replace a newer event", async () => {
    const fake = fakeTransport({ snapshot: snapshotOf({ seq: 1 }) });
    const slow = fake.transport.invoke;
    fake.transport.invoke = async (command, args) => {
      await new Promise((resolve) => setTimeout(resolve, 20));
      return slow(command, args);
    };
    render(
      <I18nProvider locale="en">
        <IpcProvider client={createClient(fake.transport)}>
          <SnapshotProvider>
            <Seq />
          </SnapshotProvider>
        </IpcProvider>
      </I18nProvider>,
    );
    await waitFor(() => expect(fake.calls.length).toBeGreaterThan(0));
    act(() => fake.emit("snapshot", snapshotOf({ seq: 4 })));
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(screen.getByTestId("seq")).toHaveTextContent("4");
  });
});
