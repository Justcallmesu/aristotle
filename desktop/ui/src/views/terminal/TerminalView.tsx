import { useState } from "react";
import { Banner } from "../../components/Banner";
import { ActionButton, Button } from "../../components/Button";
import { CodeBlock } from "../../components/CodeBlock";
import { CommandFailed } from "../../components/CommandFailed";
import { NeedsServer } from "../../components/NeedsServer";
import { DASH } from "../../format/dash";
import { Switch } from "../../components/Switch";
import { ViewHeader } from "../../components/ViewHeader";
import { useI18n } from "../../i18n";
import { useIpc, type TerminalStatus } from "../../ipc";
import { useActionGuard, useSnapshot } from "../../state/snapshot";
import { useCommand } from "../../state/useCommand";
import { TerminalDialogs, type TerminalDialog } from "./TerminalDialogs";
import { GrantsGroup, OpenGroup } from "./TerminalLists";
import { useTerminalData } from "./useTerminal";
import "./terminal.css";

/** design.md 4.8: the remote terminal switch, who may use it, and what is open now. */
export function TerminalView() {
  const { t } = useI18n();
  const { world } = useSnapshot();
  return (
    <div className="tm-view">
      <ViewHeader title={t("t_title")} subtitle={t("t_sub")} />
      {world.row === 0 ? (
        <p role="status">{t("st_loading")}</p>
      ) : world.running ? (
        <TerminalBody />
      ) : (
        <NeedsServer />
      )}
    </div>
  );
}

function TerminalBody() {
  const { t } = useI18n();
  const client = useIpc();
  const guard = useActionGuard();
  const state = useTerminalData();
  const { data, open, devices, granted } = state;
  const [dialog, setDialog] = useState<TerminalDialog | null>(null);
  const [checking, setChecking] = useState(false);
  const act = useCommand((job: () => Promise<TerminalStatus>) => job());

  const enabled = data?.enabled ?? false;
  const problem = data?.problem ?? null;
  const locked = guard.disabled || act.working || checking;
  const switchLocked = locked || problem !== null || data === null;

  async function perform(job: () => Promise<TerminalStatus>) {
    const result = await act.run(job);
    if (result) state.apply(result);
  }

  /** D19-8: decide from a fresh read, not the poll (a terminal may have opened in the last 5 s). */
  async function turnOff() {
    setChecking(true);
    const openNow = await state.freshOpen();
    setChecking(false);
    if (openNow.length > 0) setDialog({ kind: "off", open: openNow.length });
    else void perform(() => client.terminalDisable());
  }

  function confirm() {
    const chosen = dialog;
    setDialog(null);
    if (!chosen) return;
    if (chosen.kind === "enable") void perform(() => client.terminalEnable());
    else if (chosen.kind === "grant") void perform(() => client.terminalGrant(chosen.id));
    else void perform(() => client.terminalDisable());
  }

  if (state.loading) return <p role="status">{t("st_loading")}</p>;

  return (
    <>
      {act.error ? <CommandFailed error={act.error} /> : null}
      {problem !== null ? (
        <Banner
          role="alert"
          light="err"
          title={t("t_prob_t")}
          actions={
            <Button variant="tonal" disabled={act.working} onClick={() => void state.reread()}>
              {t("t_check")}
            </Button>
          }
        >
          <p>{t("t_prob_b")}</p>
          <CodeBlock>{`problem: ${problem}`}</CodeBlock>
        </Banner>
      ) : data?.warning ? (
        <Banner role="status" light="warn" title={t("t_warn_t")}>
          <CodeBlock>{`warning: ${data.warning}`}</CodeBlock>
        </Banner>
      ) : null}
      <div className="tm-block">
        <div className="kv-state">
          <div className="tm-state-main">
            <Switch
              checked={enabled}
              label={enabled ? t("t_on") : t("t_off")}
              disabled={switchLocked}
              onChange={(next) => (next ? setDialog({ kind: "enable" }) : void turnOff())}
            />
            <p className="tm-desc">{data === null ? DASH : enabled ? t("t_on_d") : t("t_off_d")}</p>
          </div>
          {!enabled && problem === null ? (
            <ActionButton variant="primary" disabled={switchLocked} onClick={() => setDialog({ kind: "enable" })}>
              {t("t_turn_on")}
            </ActionButton>
          ) : null}
        </div>
        {enabled && problem === null ? <p className="hint">{t("t_off_hint")}</p> : null}
        {guard.reasonKey ? <p className="hint">{t(guard.reasonKey)}</p> : null}
      </div>
      {problem === null ? (
        <div className="tm-cols">
          <GrantsGroup
            granted={granted}
            devices={devices}
            busy={locked}
            onRemove={(id) => void perform(() => client.terminalUngrant(id))}
            onGrant={(id, name) => setDialog({ kind: "grant", id, name })}
          />
          {enabled ? <OpenGroup open={open} devices={devices} now={Date.now()} /> : null}
        </div>
      ) : null}

      {dialog ? (
        <TerminalDialogs
          dialog={dialog}
          noGrants={(granted?.length ?? 0) === 0}
          onConfirm={confirm}
          onCancel={() => setDialog(null)}
        />
      ) : null}
    </>
  );
}
