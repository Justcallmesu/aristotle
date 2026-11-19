import { useCallback, useEffect, useState } from "react";
import { ActionButton } from "../../components/Button";
import { CommandFailed } from "../../components/CommandFailed";
import { NeedsServer } from "../../components/NeedsServer";
import { ViewHeader } from "../../components/ViewHeader";
import { useI18n } from "../../i18n";
import { useIpc, type Pair, type PairAddress } from "../../ipc";
import { useStartIntent } from "../../state/intent";
import { useActionGuard, useSnapshot } from "../../state/snapshot";
import { useCommand } from "../../state/useCommand";
import { CodePanel, ExpiredPanel, NoListenerPanel } from "./PairPanels";
import "./pair.css";

/**
 * What the view holds (P11): the code, payload and fingerprint live in this component's state only,
 * and are dropped on expiry and on unmount. Nothing here is logged or stored elsewhere.
 */
type Shown =
  | { kind: "code"; pair: Pair; expiresAt: number }
  | { kind: "expired"; addresses: PairAddress[]; expiresAt: number }
  | { kind: "none"; error: string | null };

function expiryOf(pair: Pair): number {
  const at = Date.parse(pair.expires_at);
  return Number.isNaN(at) ? 0 : at;
}

function shownFor(pair: Pair): Shown {
  // A QR that could not be drawn does not hide a valid code: only a missing payload means no listener.
  if (!pair.qr_payload) return { kind: "none", error: pair.qr_error ?? null };
  return { kind: "code", pair, expiresAt: expiryOf(pair) };
}

export function PairView() {
  const { t } = useI18n();
  const { snapshot, world } = useSnapshot();
  const { pending, acknowledge } = useStartIntent();
  const stray = pending?.intent.kind === "pair" && snapshot !== null && !world.running ? pending.id : null;
  useEffect(() => {
    if (stray !== null) acknowledge(stray); // nothing to issue a code on: drop the hand-off
  }, [stray, acknowledge]);

  if (!snapshot) {
    return (
      <>
        <ViewHeader title={t("pair_title")} />
        <p role="status">{t("st_loading")}</p>
      </>
    );
  }
  if (!world.running) {
    return (
      <>
        <ViewHeader title={t("pair_title")} subtitle={t("pair_sub")} />
        <NeedsServer />
      </>
    );
  }
  return <PairFlow />;
}

function PairFlow() {
  const { t } = useI18n();
  const client = useIpc();
  const guard = useActionGuard();
  const { pending, acknowledge } = useStartIntent();
  const [shown, setShown] = useState<Shown | null>(null);
  const [name, setName] = useState("");
  const nameInvalid = name.trim().startsWith("-");

  // The command never keeps the reply: `useCommand` would hold the code after expiry.
  const issue = useCommand(async (given: string) => {
    setShown(shownFor(await client.pair(given || null)));
    return null;
  });
  const runIssue = issue.run;
  const make = useCallback(() => void runIssue(name.trim()), [runIssue, name]);

  // The tray's "Pair a phone…" (P14): issue a code at once; under a mismatch only drop the hand-off.
  const pairIntent = pending?.intent.kind === "pair" ? pending.id : null;
  useEffect(() => {
    if (pairIntent === null) return;
    acknowledge(pairIntent);
    if (!guard.disabled) void runIssue("");
  }, [pairIntent, acknowledge, guard.disabled, runIssue]);

  const expire = useCallback(
    () =>
      setShown((current) =>
        current?.kind === "code"
          ? { kind: "expired", addresses: current.pair.addresses, expiresAt: current.expiresAt }
          : current,
      ),
    [],
  );

  const busy = issue.working;
  const label = busy ? t("pair_busy") : null;
  const failed = issue.error ? <CommandFailed error={issue.error} /> : null;
  const disabled = busy || nameInvalid;
  const hint = guard.reasonKey ? <p className="hint">{t(guard.reasonKey)}</p> : null;

  if (shown === null) {
    return (
      <>
        <ViewHeader title={t("pair_title")} subtitle={t("pair_sub")} />
        {failed}
        <form
          className="pair-form"
          onSubmit={(e) => {
            e.preventDefault();
            if (!disabled && !guard.disabled) make();
          }}
        >
          <label className="field-label" htmlFor="pair-name">{t("pair_name")}</label>
          <input
            id="pair-name"
            className="field-input"
            value={name}
            placeholder="pixel"
            autoComplete="off"
            aria-invalid={nameInvalid}
            aria-describedby={nameInvalid ? "pair-name-err" : "pair-name-hint"}
            onChange={(e) => setName(e.target.value)}
          />
          {nameInvalid ? (
            <p id="pair-name-err" className="field-error">{t("pair_name_err")}</p>
          ) : (
            <p id="pair-name-hint" className="hint hint-field">{t("pair_name_hint")}</p>
          )}
          <ActionButton type="submit" variant="primary" disabled={disabled}>
            {label ?? t("pair_show")}
          </ActionButton>
          {hint}
        </form>
      </>
    );
  }

  const again = (variant: "outline" | "primary", text: string) => (
    <ActionButton variant={variant} disabled={busy || nameInvalid} onClick={make}>
      {label ?? text}
    </ActionButton>
  );

  return (
    <>
      <ViewHeader title={t("pair_title")} />
      {failed}
      {shown.kind === "code" ? (
        <CodePanel pair={shown.pair} expiresAt={shown.expiresAt} onExpire={expire} action={again("outline", t("pair_new"))} />
      ) : shown.kind === "expired" ? (
        <ExpiredPanel addresses={shown.addresses} expiresAt={shown.expiresAt} action={again("primary", t("pair_new"))} />
      ) : (
        <NoListenerPanel error={shown.error} action={again("primary", t("pair_try"))} />
      )}
      {hint}
    </>
  );
}
