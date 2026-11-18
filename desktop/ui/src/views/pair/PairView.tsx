import { useCallback, useEffect, useState } from "react";
import { ActionButton } from "../../components/Button";
import { CodeBlock } from "../../components/CodeBlock";
import { CommandFailed } from "../../components/CommandFailed";
import { CopyButton } from "../../components/CopyButton";
import { NeedsServer } from "../../components/NeedsServer";
import { Progress } from "../../components/Progress";
import { ViewHeader } from "../../components/ViewHeader";
import { useI18n } from "../../i18n";
import { useIpc, type Pair, type PairAddress } from "../../ipc";
import { useStartIntent } from "../../state/intent";
import { useActionGuard, useSnapshot } from "../../state/snapshot";
import { useCommand } from "../../state/useCommand";
import { clock } from "../devices/time";
import { QrTile } from "./QrTile";
import { modulesOf } from "./qr";
import { mmss, useCountdown } from "./useCountdown";
import "./pair.css";

const LIFETIME_SECONDS = 300;

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
  if (pair.qr_error || !pair.qr_payload) return { kind: "none", error: pair.qr_error ?? null };
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
  const hint = guard.reasonKey ? <p className="reason-hint">{t(guard.reasonKey)}</p> : null;

  if (shown === null) {
    return (
      <>
        {failed}
        <ViewHeader title={t("pair_title")} subtitle={t("pair_sub")} />
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
            <p id="pair-name-hint" className="field-hint">{t("pair_name_hint")}</p>
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
      {failed}
      <ViewHeader title={t("pair_title")} />
      {shown.kind === "code" ? (
        <CodePanel pair={shown.pair} expiresAt={shown.expiresAt} onExpire={expire} action={again("outline", t("pair_new"))} />
      ) : shown.kind === "expired" ? (
        <div className="pair-code-layout">
          <div className="pair-left">
            <div className="qr-dead">
              <strong>{t("pair_expired")}</strong>
              <span>{t("pair_expired_b", { t: clock(new Date(shown.expiresAt)) })}</span>
            </div>
          </div>
          <div className="pair-right">
            <Addresses addresses={shown.addresses} />
            {again("primary", t("pair_new"))}
          </div>
        </div>
      ) : (
        <div className="pair-code-layout">
          <div className="pair-left">
            <div className="qr-dead">
              <strong>{t("pair_none_t")}</strong>
            </div>
          </div>
          <div className="pair-right">
            <p className="pair-section">{t("pair_none_b")}</p>
            {shown.error ? (
              <div className="pair-section">
                <h2 className="pair-label">{t("details")}</h2>
                <CodeBlock>{`qr_error: ${shown.error}`}</CodeBlock>
              </div>
            ) : null}
            {again("primary", t("pair_try"))}
          </div>
        </div>
      )}
      {hint}
    </>
  );
}

function CodePanel({
  pair,
  expiresAt,
  onExpire,
  action,
}: {
  pair: Pair;
  expiresAt: number;
  onExpire: () => void;
  action: React.ReactNode;
}) {
  const { t } = useI18n();
  const seconds = useCountdown(expiresAt);
  useEffect(() => {
    if (seconds === 0) onExpire();
  }, [seconds, onExpire]);
  const rows = modulesOf(pair);
  const minutes = Math.ceil(seconds / 60);
  const fingerprint = pair.fingerprint.replace(/\s+/g, "").match(/.{1,4}/g) ?? [];

  return (
    <div className="pair-code-layout">
      <div className="pair-left">
        {rows ? <QrTile rows={rows} /> : null}
        <p className="countdown">{t("pair_expires", { t: mmss(seconds) })}</p>
        <Progress label={t("pair_expires", { t: mmss(seconds) })} value={Math.min(seconds, LIFETIME_SECONDS)} max={LIFETIME_SECONDS} />
        <span className="sr-only" aria-live="polite">
          {t("pair_expires", { t: t("dur_min", { n: minutes }) })}
        </span>
      </div>
      <div className="pair-right">
        <div className="pair-section">
          <h2 className="pair-label">{t("pair_code")}</h2>
          <div className="code-row">
            <span className="pair-code" data-sensitive="">
              {pair.code.slice(0, 4)} {pair.code.slice(4)}
            </span>
            <CopyButton text={pair.code} />
          </div>
          <p className="pair-hint">{t("pair_scan")}</p>
        </div>
        <div className="pair-section">
          <h2 className="pair-label">{t("pair_fp")}</h2>
          <div className="fp-card" data-sensitive="">
            {fingerprint.map((group, i) => (
              <span key={i}>{group}</span>
            ))}
          </div>
          <p className="pair-hint">{t("pair_fp_hint")}</p>
        </div>
        <Addresses addresses={pair.addresses} />
        {action}
      </div>
    </div>
  );
}

function Addresses({ addresses }: { addresses: PairAddress[] }) {
  const { t, word } = useI18n();
  return (
    <div className="pair-section">
      <h2 className="pair-label">{t("pair_addr")}</h2>
      <ul className="addr-list">
        {addresses.map((a) => (
          <li key={a.url} className="addr-row">
            <span className="addr-url">{a.url}</span>
            <span className="addr-meta">
              {word("kind_", a.kind)} · {a.iface}
              {a.default_route ? ` · ${t("default_route")}` : ""}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
