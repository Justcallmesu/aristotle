import { useI18n, type MessageKey } from "../i18n";
import { useIpc } from "../ipc";
import { useSnapshot } from "../state/snapshot";
import { VIEWS, type View } from "../state/route";
import { useEffect, useState } from "react";
import { StatusFooter } from "./StatusFooter";

const groups: { header: MessageKey; items: View[] }[] = [
  { header: "grp_server", items: ["status", "service", "logs"] },
  { header: "grp_phones", items: ["pair", "devices", "terminal"] },
];

const labelKey: Record<View, MessageKey> = {
  status: "nav_status",
  service: "nav_service",
  logs: "nav_logs",
  pair: "nav_pair",
  devices: "nav_devices",
  terminal: "nav_terminal",
};

// Every view appears in exactly one group.
if (groups.flatMap((g) => g.items).length !== VIEWS.length) {
  throw new Error("sidebar groups must list every view");
}

/** The Terminal count needs `enabled`, which the snapshot does not carry: read it per poll while running. */
function useTerminalEnabled(): boolean | null {
  const client = useIpc();
  const { snapshot, world } = useSnapshot();
  const [enabled, setEnabled] = useState<boolean | null>(null);
  useEffect(() => {
    if (!world.running) {
      setEnabled(null);
      return;
    }
    let alive = true;
    client
      .terminalStatus()
      .then((s) => alive && setEnabled(s.enabled))
      .catch(() => alive && setEnabled(null));
    return () => {
      alive = false;
    };
  }, [client, snapshot, world.running]);
  return enabled;
}

export function Sidebar({ current, onOpen }: { current: View; onOpen: (view: View) => void }) {
  const { t } = useI18n();
  const { snapshot, world } = useSnapshot();
  const terminalEnabled = useTerminalEnabled();
  const counts: Partial<Record<View, string>> = {};
  if (world.running) {
    if (snapshot?.devices) counts.devices = String(snapshot.devices.length);
    if (terminalEnabled !== null) counts.terminal = t(terminalEnabled ? "t_on" : "t_off");
  }

  return (
    <aside className="side">
      <div className="side-brand">
        <span className="wordmark">Aristotle</span>
        <span className="side-brand-sub">Server</span>
      </div>
      <nav aria-label={t("nav_label")}>
        {groups.map((group) => (
          <div key={group.header} className="nav-group">
            <div className="nav-group-title">{t(group.header)}</div>
            {group.items.map((view) => (
              <button
                key={view}
                type="button"
                className="nav-item"
                aria-current={view === current ? "page" : undefined}
                onClick={() => onOpen(view)}
              >
                <span>{t(labelKey[view])}</span>
                {counts[view] ? <span className="nav-count">{counts[view]}</span> : null}
              </button>
            ))}
          </div>
        ))}
      </nav>
      <StatusFooter />
    </aside>
  );
}
