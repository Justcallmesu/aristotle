import { useEffect, useState } from "react";
import { Button } from "../../components/Button";
import { CommandFailed } from "../../components/CommandFailed";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { Progress } from "../../components/Progress";
import { KvGroup, KvRow, StateRow } from "../../components/Rows";
import { StateLight } from "../../components/StateLight";
import { ViewHeader } from "../../components/ViewHeader";
import { useI18n, type Params, type Translator } from "../../i18n";
import { enabledWord, serviceWord, statusOf } from "../../state/facts";
import { useStartIntent } from "../../state/intent";
import { serviceButtons } from "../../state/serviceButtons";
import { useSnapshot } from "../../state/snapshot";
import { useServiceAction } from "../../state/useServiceAction";
import "./service.css";

type Dialog = "stop" | "restart" | null;

export function ServiceView() {
  const tr = useI18n();
  const { t } = tr;
  const { snapshot, world } = useSnapshot();
  const status = statusOf(snapshot);
  const action = useServiceAction();
  const [dialog, setDialog] = useState<Dialog>(null);
  const buttons = serviceButtons(status, world, action.busy);
  const reasonText = buttons.reason ? t(buttons.reason.key, wordParams(tr, buttons.reason.params)) : null;
  useConfirmStopIntent(world.row !== 0, buttons.stop, () => setDialog("stop"));

  function confirm(kind: "stop" | "restart") {
    setDialog(null);
    void action.run(kind);
  }

  return (
    <>
      <ViewHeader title={t("svc_title")} subtitle={t("svc_sub")} />
      {action.error ? <CommandFailed error={action.error} /> : null}
      <StateRow
        busy={world.row === 0}
        light={world.light ? <StateLight light={world.light} /> : null}
        title={t(world.titleKey)}
        sub={world.subKey ? t(world.subKey, world.subParams) : undefined}
      />
      <KvGroup title={t("svc_state")}>
        <KvRow label={t("svc_unit")}>
          <span className="mono">aristotle-server.service · systemd --user</span>
        </KvRow>
        <KvRow label={t("k_service")}>{serviceWord(tr, status)}</KvRow>
        <KvRow label={t("k_enabled")}>{enabledWord(tr, status)}</KvRow>
      </KvGroup>
      <div className="svc-buttons">
        <Button variant={buttons.start ? "primary" : "default"} disabled={!buttons.start} onClick={() => void action.run("start")}>
          {t("b_start")}
        </Button>
        <Button variant="outline-danger" disabled={!buttons.stop} onClick={() => setDialog("stop")}>
          {t("b_stop")}
        </Button>
        <Button variant="tonal" disabled={!buttons.restart} onClick={() => setDialog("restart")}>
          {t("b_restart")}
        </Button>
      </div>
      {action.busy && action.action ? (
        <div className="svc-working">
          <Progress label={t(`working_${action.action}`)} />
          <p className="hint">{t(`working_${action.action}`)}</p>
        </div>
      ) : buttons.reason ? (
        <p className="hint svc-reason">{reasonText}</p>
      ) : null}
      {dialog === "stop" ? (
        <ConfirmDialog title={t("d_stop_t")} confirmLabel={t("b_stop")} onConfirm={() => confirm("stop")} onCancel={() => setDialog(null)}>
          <p>{t("d_stop_b")}</p>
        </ConfirmDialog>
      ) : null}
      {dialog === "restart" ? (
        <ConfirmDialog
          title={t("d_restart_t")}
          confirmLabel={t("b_restart")}
          confirmVariant="primary"
          onConfirm={() => confirm("restart")}
          onCancel={() => setDialog(null)}
        >
          <p>{t("d_restart_b")}</p>
        </ConfirmDialog>
      ) : null}
    </>
  );
}

/** `{word}` is a systemctl word: the catalog's `svc_*` text, or the raw word when it has none. */
function wordParams(tr: Translator, params: Params | undefined): Params | undefined {
  return params && "word" in params ? { ...params, word: tr.word("svc_", String(params.word)) } : params;
}

/**
 * `--confirm stop` (P14): the tray hands the window a start intent; this view opens the stop dialog
 * once. It waits for the first poll, and only opens the dialog when Stop is actually available.
 */
function useConfirmStopIntent(polled: boolean, stopAvailable: boolean, open: () => void) {
  const { pending, acknowledge } = useStartIntent();
  const id = pending?.intent.kind === "confirm-stop" ? pending.id : null;
  useEffect(() => {
    if (id === null || !polled) return;
    acknowledge(id);
    if (stopAvailable) open();
    // `open` only sets state; the intent is handled once per id.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, polled]);
}
