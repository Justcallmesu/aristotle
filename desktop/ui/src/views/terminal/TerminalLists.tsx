import { useState } from "react";
import { ActionButton } from "../../components/Button";
import { useI18n } from "../../i18n";
import { DASH } from "../../format/dash";
import { clockText, minutesSince } from "../../format/time";
import type { Device, GrantedDevice, OpenTerminal } from "../../ipc";

interface GrantsProps {
  /** Null when the poll could not read the list. */
  granted: GrantedDevice[] | null;
  devices: Device[];
  /** False when the device list could not be read: an unnamed grant shows its id, not "(no longer paired)". */
  devicesKnown: boolean;
  busy: boolean;
  onRemove: (id: string) => void;
  onGrant: (id: string, name: string) => void;
}

/** Left column: who holds a grant, and the add row. Shown whether the switch is on or off. */
export function GrantsGroup({ granted: list, devices, devicesKnown, busy, onRemove, onGrant }: GrantsProps) {
  const { t } = useI18n();
  const [chosen, setChosen] = useState("");
  const granted = list ?? [];
  const free = devices.filter((d) => !granted.some((g) => g.id === d.id));
  const selected = free.find((d) => d.id === chosen) ?? free[0];

  return (
    <section className="kv-group" aria-labelledby="tm-grants">
      <h3 id="tm-grants" className="group-title">
        {t("t_grants")}
      </h3>
      <div className="kv-rows">
      {list === null ? (
        <div className="kv-row">{DASH}</div>
      ) : granted.length === 0 ? (
        <div className="kv-row">{t("t_grants_empty")}</div>
      ) : (
        granted.map((g) => {
          const name = g.name || devices.find((d) => d.id === g.id)?.name || "";
          return (
            <div className="kv-row" key={g.id}>
              <div className="kv-val">
                <strong>{name || (devicesKnown ? t("t_gone") : g.id)}</strong>
                <div className="kv-sub">{g.id}</div>
              </div>
              <ActionButton variant="text-danger" small disabled={busy} onClick={() => onRemove(g.id)}>
                {t("t_remove")}
              </ActionButton>
            </div>
          );
        })
      )}
      {list !== null && devices.length > 0 ? (
        free.length === 0 ? (
          <div className="kv-row">{t("t_grant_all")}</div>
        ) : (
          <div className="kv-row tm-add">
            <label htmlFor="tm-grant-to">{t("t_grant_to")}</label>
            <select
              id="tm-grant-to"
              className="tm-select"
              value={selected?.id ?? ""}
              disabled={busy}
              onChange={(e) => setChosen(e.target.value)}
            >
              {free.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name || d.id}
                </option>
              ))}
            </select>
            <ActionButton
              variant="tonal"
              small
              disabled={busy || !selected}
              onClick={() => selected && onGrant(selected.id, selected.name || selected.id)}
            >
              {t("t_grant")}
            </ActionButton>
          </div>
        )
      ) : null}
      </div>
    </section>
  );
}

interface OpenProps {
  open: OpenTerminal[];
  devices: Device[];
  now: number;
}

/** Right column (switch on only): one row per open terminal. */
export function OpenGroup({ open, devices, now }: OpenProps) {
  const { t, word } = useI18n();
  return (
    <section className="kv-group" aria-labelledby="tm-open">
      <h3 id="tm-open" className="group-title">
        {t("t_open")}
      </h3>
      <div className="kv-rows">
      {open.length === 0 ? (
        <div className="kv-row">{t("t_open_empty")}</div>
      ) : (
        open.map((o) => {
          const minutes = minutesSince(o.since, now);
          const parts = [
            word("kind_", o.listener),
            clockText(o.since),
            minutes === null ? DASH : t("dur_min", { n: minutes }),
            `${t("c_pid")} ${o.pid}`,
          ];
          return (
            <div className="kv-row" key={o.id}>
              <div className="kv-val">
                <strong>{devices.find((d) => d.id === o.device_id)?.name || o.device_id}</strong>
                <div className="kv-sub">{parts.join(" · ")}</div>
              </div>
              <span className="kv-sub kv-end">{o.id}</span>
            </div>
          );
        })
      )}
      </div>
    </section>
  );
}
