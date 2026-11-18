import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { I18nProvider, type Locale } from "../i18n";
import { createClient, IpcProvider, type SnapshotDto, type Status } from "../ipc";
import { IntentProvider } from "../state/intent";
import { SnapshotProvider } from "../state/snapshot";
import { fakeTransport, snapshotOf } from "./fakes";

/** A snapshot whose `status` answer is the default running one with `patch` on top. */
export function withStatus(patch: Partial<Status>, rest: Partial<SnapshotDto> = {}): SnapshotDto {
  const base = snapshotOf();
  if (base.status.kind !== "answered") throw new Error("fixture");
  return { ...base, ...rest, status: { kind: "answered", status: { ...base.status.status, ...patch } } };
}

export function stoppedSnapshot(patch: Partial<Status> = {}, rest: Partial<SnapshotDto> = {}): SnapshotDto {
  return withStatus({ service: "inactive", server: "not-running", result: null, ...patch }, rest);
}

export function runningWith(result: Record<string, unknown>, rest: Partial<SnapshotDto> = {}): SnapshotDto {
  const base = withStatus({});
  if (base.status.kind !== "answered") throw new Error("fixture");
  const status = base.status.status;
  return { ...base, ...rest, status: { kind: "answered", status: { ...status, result: { ...status.result!, ...result } } } };
}

export interface Rendered {
  fake: ReturnType<typeof fakeTransport>;
}

/** The view inside the real providers (incl. the start intent) over a fake transport. */
export function renderView(
  ui: ReactElement,
  replies: Record<string, unknown>,
  options: { locale?: Locale } = {},
): Rendered {
  const fake = fakeTransport({ start_intent: { kind: "status" }, ...replies });
  render(
    <I18nProvider locale={options.locale ?? "en"}>
      <IpcProvider client={createClient(fake.transport)}>
        <SnapshotProvider>
          <IntentProvider>{ui}</IntentProvider>
        </SnapshotProvider>
      </IpcProvider>
    </I18nProvider>,
  );
  return { fake };
}

/** Holds one command open until the test resolves or rejects it (works before or after the call). */
export function gate(fake: ReturnType<typeof fakeTransport>, command: string) {
  let resolve: (v: unknown) => void = () => undefined;
  let reject: (e: unknown) => void = () => undefined;
  const held = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  held.catch(() => undefined);
  fake.setReply(command, () => held);
  return { resolve: (v: unknown) => resolve(v), reject: (e: unknown) => reject(e) };
}

export function called(fake: ReturnType<typeof fakeTransport>, command: string) {
  return fake.calls.filter((c) => c.command === command);
}
