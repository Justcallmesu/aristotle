import { useEffect, type ReactNode } from "react";
import { CodeBlock } from "../../components/CodeBlock";
import { CopyButton } from "../../components/CopyButton";
import { Progress } from "../../components/Progress";
import { clock } from "../../format/time";
import { useI18n } from "../../i18n";
import type { Pair, PairAddress } from "../../ipc";
import { QrTile } from "./QrTile";
import { modulesOf } from "./qr";
import { mmss, useCountdown } from "./useCountdown";

export const LIFETIME_SECONDS = 300;

interface CodePanelProps {
  pair: Pair;
  expiresAt: number;
  onExpire: () => void;
  action: ReactNode;
}

/** design.md 4.6 state 2: the QR tile, the countdown, the code, the fingerprint and the addresses. */
export function CodePanel({ pair, expiresAt, onExpire, action }: CodePanelProps) {
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
        {pair.qr_error ? <QrDetails error={pair.qr_error} /> : null}
        {action}
      </div>
    </div>
  );
}

/** The server could not draw the QR but did issue a code: the code stays, the reason sits under Details. */
function QrDetails({ error }: { error: string }) {
  const { t } = useI18n();
  return (
    <div className="pair-section">
      <h2 className="pair-label">{t("details")}</h2>
      <CodeBlock>{`qr_error: ${error}`}</CodeBlock>
    </div>
  );
}

/** design.md 4.6 state 3: the code ran out; the addresses stay, the code is gone. */
export function ExpiredPanel({ addresses, expiresAt, action }: { addresses: PairAddress[]; expiresAt: number; action: ReactNode }) {
  const { t } = useI18n();
  return (
    <div className="pair-code-layout">
      <div className="pair-left">
        <div className="qr-dead">
          <strong>{t("pair_expired")}</strong>
          <span>{t("pair_expired_b", { t: clock(new Date(expiresAt)) })}</span>
        </div>
      </div>
      <div className="pair-right">
        <Addresses addresses={addresses} />
        {action}
      </div>
    </div>
  );
}

/** design.md 4.6 state 4: the server has no address to pair on. */
export function NoListenerPanel({ error, action }: { error: string | null; action: ReactNode }) {
  const { t } = useI18n();
  return (
    <div className="pair-code-layout">
      <div className="pair-left">
        <div className="qr-dead">
          <strong>{t("pair_none_t")}</strong>
        </div>
      </div>
      <div className="pair-right">
        <p className="pair-section">{t("pair_none_b")}</p>
        {error ? <QrDetails error={error} /> : null}
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
