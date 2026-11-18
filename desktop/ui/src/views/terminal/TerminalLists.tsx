import { useState } from "react";
import { ActionButton } from "../../components/Button";
import { useI18n } from "../../i18n";
import type { Device, OpenTerminal } from "../../ipc";
import type { TerminalData } from "./useTerminal";
import { clock, minutesSince } from "./format";

interface GrantsProps {
  data: TerminalData | null;
  devices: Device[];
  busy: boolean;
  onRemove: (id: string) => void;
  onGrant: (id: string, name: string) => void;
}

/** Left column: who holds a grant, and the add row. Shown whether the switch is on or off. */
export function GrantsGroup({ data, devices, busy, onRemove, onGrant }: GrantsProps) {
  const { t } = useI18n();
  const [chosen, setChosen] = useState("");
  const granted = data?.granted ?? [];
  const free = devices.filter((d) => !granted.some((g) => g.id === d.id));
  const selected = free.find((d) => d.id === chosen) ?? free[0];

  return (
    <section className="tm-group" aria-labelledby="tm-grants">
      <h3 id="tm-grants" className="group-title">
        {t("t_grants")}
      </h3>
      {data === null ? (
        <div className="tm-row">—</div>
      ) : granted.length === 0 ? (
        <div className="tm-row">{t("t_grants_empty")}</div>
      ) : (
        granted.map((g) => {
          const name = g.name || devices.find((d) => d.id === g.id)?.name || "";
          return (
            <div className="tm-row" key={g.id}>
              <div className="tm-row-text">
                <strong>{name || t("t_gone")}</strong>
                <span className="tm-sub">{g.id}</span>
              </div>
              <ActionButton variant="text-danger" small disabled={busy} onClick={() => onRemove(g.id)}>
                {t("t_remove")}
              </ActionButton>
            </div>
          );
        })
      )}
      {data !== null && devices.length > 0 ? (
        free.length === 0 ? (
          <div className="tm-row">{t("t_grant_all")}</div>
        ) : (
          <div className="tm-row tm-add">
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
    <section className="tm-group" aria-labelledby="tm-open">
      <h3 id="tm-open" className="group-title">
        {t("t_open")}
      </h3>
      {open.length === 0 ? (
        <div className="tm-row">{t("t_open_empty")}</div>
      ) : (
        open.map((o) => {
          const minutes = minutesSince(o.since, now);
          const parts = [
            word("kind_", o.listener),
            clock(o.since),
            minutes === null ? "—" : t("dur_min", { n: minutes }),
            `${t("c_pid")} ${o.pid}`,
          ];
          return (
            <div className="tm-row" key={o.id}>
              <div className="tm-row-text">
                <strong>{devices.find((d) => d.id === o.device_id)?.name || o.device_id}</strong>
                <span className="tm-sub">{parts.join(" · ")}</span>
              </div>
              <span className="tm-end">{o.id}</span>
            </div>
          );
        })
      )}
    </section>
  );
}
