import { act, renderHook } from "@testing-library/react";
import { useCommand } from "./useCommand";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe("useCommand", () => {
  it("starts idle", () => {
    const { result } = renderHook(() => useCommand(async () => 1));
    expect(result.current).toMatchObject({ working: false, succeeded: false, failed: false, error: null });
  });

  it("is working while the command runs, then succeeded with the result", async () => {
    const d = deferred<number>();
    const { result } = renderHook(() => useCommand((n: number) => d.promise.then((v) => v + n * 0)));
    let done: Promise<unknown> = Promise.resolve();
    act(() => {
      done = result.current.run(7);
    });
    expect(result.current.working).toBe(true);
    await act(async () => {
      d.resolve(42);
      await done;
    });
    expect(result.current).toMatchObject({ working: false, succeeded: true, failed: false, result: 42 });
  });

  it("treats a resolved { ok: false } as failed and keeps the result", async () => {
    const value = { ok: false, detail: "x" };
    const { result } = renderHook(() => useCommand(async () => value));
    await act(async () => {
      await result.current.run();
    });
    expect(result.current).toMatchObject({ succeeded: false, failed: true, result: value });
    expect(result.current.error).toEqual({ kind: "not-ok", message: null, exit_code: null });
  });

  it.each([[{ ok: true }], [5], [null], ["s"], [undefined]])("treats resolved %j as succeeded", async (value) => {
    const { result } = renderHook(() => useCommand(async () => value));
    await act(async () => {
      await result.current.run();
    });
    expect(result.current).toMatchObject({ succeeded: true, failed: false, error: null });
  });

  it("does not start a second run while one is working", async () => {
    const d = deferred<number>();
    const command = vi.fn(() => d.promise);
    const { result } = renderHook(() => useCommand(command));
    act(() => {
      void result.current.run();
      void result.current.run();
    });
    expect(command).toHaveBeenCalledTimes(1);
    await act(async () => d.resolve(1));
  });

  it("keeps the error kind and the server message when the command rejects", async () => {
    const { result } = renderHook(() =>
      useCommand(async () => {
        throw { kind: "server-error", message: "refused: inside a terminal", exit_code: 1 };
      }),
    );
    await act(async () => {
      await result.current.run();
    });
    expect(result.current.failed).toBe(true);
    expect(result.current.error).toEqual({ kind: "server-error", message: "refused: inside a terminal", exit_code: 1 });
  });

  it("turns anything that is not an IPC error into an internal one", async () => {
    const { result } = renderHook(() =>
      useCommand(async () => {
        throw new Error("boom");
      }),
    );
    await act(async () => {
      await result.current.run();
    });
    expect(result.current.error).toEqual({ kind: "internal", message: null, exit_code: null });
  });

  it("clears the failure on the next run", async () => {
    let fail = true;
    const { result } = renderHook(() =>
      useCommand(async () => {
        if (fail) throw { kind: "timeout", message: null, exit_code: null };
        return "ok";
      }),
    );
    await act(async () => {
      await result.current.run();
    });
    expect(result.current.failed).toBe(true);
    fail = false;
    await act(async () => {
      await result.current.run();
    });
    expect(result.current).toMatchObject({ failed: false, error: null, succeeded: true });
  });

  it("clears the failure when the view changes", async () => {
    const { result } = renderHook(() =>
      useCommand(async () => {
        throw { kind: "timeout", message: null, exit_code: null };
      }),
    );
    await act(async () => {
      await result.current.run();
    });
    expect(result.current.failed).toBe(true);
    await act(async () => {
      window.location.hash = "/logs";
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });
    expect(result.current).toMatchObject({ failed: false, error: null });
    window.location.hash = "";
  });

  it("does not update after unmount", async () => {
    const d = deferred<number>();
    const errors = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const { result, unmount } = renderHook(() => useCommand(() => d.promise));
    let done: Promise<unknown> = Promise.resolve();
    act(() => {
      done = result.current.run();
    });
    unmount();
    await act(async () => {
      d.resolve(1);
      await done;
    });
    expect(errors).not.toHaveBeenCalled();
    errors.mockRestore();
  });
});
