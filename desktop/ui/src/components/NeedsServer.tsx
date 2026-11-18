import { useI18n } from "../i18n";
import { useIpc } from "../ipc";
import { canStart } from "../state/serviceButtons";
import { statusOf } from "../state/facts";
import { useSnapshot } from "../state/snapshot";
import { useCommand } from "../state/useCommand";
import { ActionButton } from "./Button";
import { CommandFailed } from "./CommandFailed";
import { EmptyState } from "./EmptyState";
import { Progress } from "./Progress";

/**
 * design.md 4.2 "needs the running server": for Pair, Devices and Terminal while `server != running`.
 * Start server shows by the one Start rule (P21, `canStart`): installed, versions match, and the
 * service is `inactive` or `failed`. The failure banner sits above the empty state, after the view header.
 */
export function NeedsServer() {
  const { t } = useI18n();
  const client = useIpc();
  const { snapshot, world } = useSnapshot();
  const start = useCommand(() => client.serviceAction("start"));
  const startable = canStart(statusOf(snapshot), world);
  return (
    <>
      {start.error ? <CommandFailed error={start.error} /> : null}
      <EmptyState
        title={t("needs_title")}
        action={
          startable ? (
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
