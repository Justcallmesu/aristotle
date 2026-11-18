import { useCallback, useEffect, useRef, useState } from "react";
import { isIpcError, type IpcError } from "../ipc";
import { useRoute } from "./route";

interface State<R> {
  phase: "idle" | "working" | "succeeded" | "failed";
  result: R | undefined;
  error: IpcError | null;
}

const IDLE = { phase: "idle", result: undefined, error: null } as const;

export interface Command<A extends unknown[], R> {
  /** Runs the command; ignored (resolves `undefined`) while one is already working. */
  run: (...args: A) => Promise<R | undefined>;
  working: boolean;
  succeeded: boolean;
  failed: boolean;
  /** The result of the last run that resolved (also kept for a `{ ok: false }` failure). */
  result: R | undefined;
  /** What a failed run rejected with: `kind` for logic, `message` is the server's own text. */
  error: IpcError | null;
  reset: () => void;
}

/** What a `{ ok: false, service }` reply says: the §22 service word, the only text the server gives. */
function notOkText(result: unknown): string | null {
  const service = (result as { service?: unknown }).service;
  return typeof service === "string" && service !== "" ? `service is ${service}` : null;
}

/**
 * Runs one IPC command for a view (design.md 4.2): working while it runs (disable the button with
 * `working`), succeeded or failed afterwards. A failure stays until the next run or a view change.
 * A resolved `{ ok: false }` result (the §22 action shape) is a failure: `error.kind` is "not-ok", `error.message`
 * is `service is <word>` and `result` keeps the value for the view to read.
 */
export function useCommand<A extends unknown[], R>(command: (...args: A) => Promise<R>): Command<A, R> {
  const [state, setState] = useState<State<R>>(IDLE);
  const latest = useRef(command);
  const busy = useRef(false);
  const mounted = useRef(true);
  const [view] = useRoute();

  useEffect(() => {
    latest.current = command;
  });
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  useEffect(() => {
    setState((s) => (s.phase === "working" ? s : IDLE));
  }, [view]);

  const run = useCallback(async (...args: A) => {
    if (busy.current) return undefined;
    busy.current = true;
    setState({ phase: "working", result: undefined, error: null });
    try {
      const result = await latest.current(...args);
      if (mounted.current) {
        const notOk = typeof result === "object" && result !== null && (result as { ok?: unknown }).ok === false;
        setState(
          notOk
            ? { phase: "failed", result, error: { kind: "not-ok", message: notOkText(result), exit_code: null } }
            : { phase: "succeeded", result, error: null },
        );
      }
      return result;
    } catch (thrown) {
      const error = isIpcError(thrown) ? thrown : { kind: "internal", message: null, exit_code: null };
      if (mounted.current) setState({ phase: "failed", result: undefined, error });
      return undefined;
    } finally {
      busy.current = false;
    }
  }, []);

  const reset = useCallback(() => setState((s) => (s.phase === "working" ? s : IDLE)), []);

  return {
    run,
    working: state.phase === "working",
    succeeded: state.phase === "succeeded",
    failed: state.phase === "failed",
    result: state.result,
    error: state.error,
    reset,
  };
}
