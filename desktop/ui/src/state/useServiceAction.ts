import { useCallback, useRef, useState } from "react";
import { useIpc, type ServiceAction } from "../ipc";
import { useSnapshot } from "./snapshot";
import { useCommand } from "./useCommand";

/**
 * Start / Stop / Restart as one command for a view (design.md 4.2). `busy` covers the run and then
 * the wait for the next poll to confirm the new state, so a button never re-enables on stale data.
 */
export function useServiceAction() {
  const client = useIpc();
  const { snapshot } = useSnapshot();
  const seq = snapshot?.seq ?? 0;
  const latestSeq = useRef(seq);
  latestSeq.current = seq;
  const [requested, setRequested] = useState<ServiceAction | null>(null);
  const [confirmAfter, setConfirmAfter] = useState<number | null>(null);
  const command = useCommand((action: ServiceAction) => client.serviceAction(action));

  const run = useCallback(
    async (action: ServiceAction) => {
      setRequested(action);
      setConfirmAfter(null);
      const result = await command.run(action);
      if (result && result.ok !== false) setConfirmAfter(latestSeq.current);
    },
    // `command.run` is stable (useCallback with no deps in useCommand).
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [command.run],
  );

  const awaitingPoll = confirmAfter !== null && seq <= confirmAfter;
  return {
    run,
    busy: command.working || awaitingPoll,
    /** Which action the working hint names. */
    action: requested,
    error: command.error,
  };
}
