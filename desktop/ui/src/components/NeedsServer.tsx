import { useI18n } from "../i18n";
import { useIpc } from "../ipc";
import { useSnapshot } from "../state/snapshot";
import { useCommand } from "../state/useCommand";
import { ActionButton } from "./Button";
import { CommandFailed } from "./CommandFailed";
import { EmptyState } from "./EmptyState";
import { Progress } from "./Progress";

/**
 * design.md 4.2 "needs the running server": for Pair, Devices and Terminal while `server != running`.
 * Start server shows only when the unit is installed, the versions match and the service is not
 * already `active` (then Start would not help).
 */
export function NeedsServer() {
  const { t } = useI18n();
  const client = useIpc();
  const { snapshot, world } = useSnapshot();
  const start = useCommand(() => client.serviceAction("start"));
  const service = snapshot?.status.kind === "answered" ? snapshot.status.status.service : null;
  const canStart = world.installed && !world.mismatch && service !== "active";

  return (
    <>
      {start.error ? <CommandFailed error={start.error} /> : null}
      <EmptyState
        title={t("needs_title")}
        action={
          canStart ? (
            <>
              <ActionButton variant="primary" disabled={start.working} onClick={() => void start.run()}>
                {t("b_start")}
              </ActionButton>
              {start.working ? (
                <>
                  <Progress label={t("working_start")} />
                  <p>{t("working_start")}</p>
                </>
              ) : null}
            </>
          ) : undefined
        }
      >
        {t("needs_body")}
      </EmptyState>
    </>
  );
}
