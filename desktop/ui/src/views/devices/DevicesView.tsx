import { useEffect, useState } from "react";
import { ActionButton, Button } from "../../components/Button";
import { CommandFailed } from "../../components/CommandFailed";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { EmptyState } from "../../components/EmptyState";
import { NeedsServer } from "../../components/NeedsServer";
import { Tag } from "../../components/Tag";
import { ViewHeader } from "../../components/ViewHeader";
import { useI18n, type Translator } from "../../i18n";
import { useIpc, type Device } from "../../ipc";
import { useRoute } from "../../state/route";
import { useActionGuard, useSnapshot } from "../../state/snapshot";
import { useCommand } from "../../state/useCommand";
import "./devices.css";
import { dayMonthYear, parseTime, relativeTime } from "./time";

const MISSING = "—";

function lastSeen(device: Device, tr: Translator): { text: string; title?: string } {
  const date = parseTime(device.last_seen_at);
  if (!date) return { text: MISSING };
  const rel = relativeTime(date, new Date(), tr.locale);
  const text =
    rel.kind === "now"
      ? tr.t("now")
      : rel.kind === "minutes"
        ? tr.t("ago_min", { n: rel.n })
        : rel.kind === "today"
          ? tr.t("today", { t: rel.time })
          : rel.text;
  return { text, title: device.last_seen_at };
}

/** Ids holding a terminal grant: one read of `terminal status`, again when the device list changes (no polling). */
function useGrantedIds(devices: Device[] | null): Set<string> | null {
  const client = useIpc();
  const [granted, setGranted] = useState<Set<string> | null>(null);
  const key = devices ? devices.map((d) => d.id).join(",") : null;
  useEffect(() => {
    if (key === null) return;
    let alive = true;
    client
      .terminalStatus()
      .then((status) => alive && setGranted(new Set((status.granted ?? []).map((g) => g.id))))
      .catch(() => undefined); // keep the last good data; the column shows — where none exists
    return () => {
      alive = false;
    };
  }, [client, key]);
  return granted;
}

export function DevicesView() {
  const { t } = useI18n();
  const { snapshot, world } = useSnapshot();
  if (!snapshot) {
    return (
      <>
        <ViewHeader title={t("dev_title")} subtitle={t("dev_sub")} />
        <p role="status">{t("st_loading")}</p>
      </>
    );
  }
  if (!world.running) {
    return (
      <>
        <ViewHeader title={t("dev_title")} subtitle={t("dev_sub")} />
        <NeedsServer />
      </>
    );
  }
  return <DeviceList devices={snapshot.devices} />;
}

function DeviceList({ devices }: { devices: Device[] | null }) {
  const tr = useI18n();
  const { t } = tr;
  const client = useIpc();
  const [, navigate] = useRoute();
  const guard = useActionGuard();
  const granted = useGrantedIds(devices);
  const [asking, setAsking] = useState<Device | null>(null);
  const revoke = useCommand((id: string) => client.devicesRevoke(id));
  const pairButton = (variant: "tonal" | "primary") => (
    <Button variant={variant} onClick={() => navigate("pair")}>
      {t("b_pair")}
    </Button>
  );

  const confirmRevoke = () => {
    const device = asking;
    setAsking(null);
    if (device) void revoke.run(device.id);
  };

  return (
    <>
      {revoke.error ? <CommandFailed error={revoke.error} /> : null}
      <ViewHeader title={t("dev_title")} subtitle={t("dev_sub")} actions={pairButton("tonal")} />
      {devices === null ? (
        <p>{MISSING}</p>
      ) : devices.length === 0 ? (
        <EmptyState title={t("empty_dev_t")} action={pairButton("primary")}>
          {t("empty_dev_b")}
        </EmptyState>
      ) : (
        <>
          <table className="dev-table">
            <thead>
              <tr>
                <th scope="col">{t("c_name")}</th>
                <th scope="col" className="col-paired">{t("c_paired")}</th>
                <th scope="col">{t("c_seen")}</th>
                <th scope="col" className="col-term">{t("c_term")}</th>
                <th aria-hidden="true" />
              </tr>
            </thead>
            <tbody>
              {devices.map((device) => {
                const seen = lastSeen(device, tr);
                const paired = parseTime(device.created_at);
                return (
                  <tr key={device.id}>
                    <td>
                      <span className="dev-name">{device.name}</span>
                      <code className="dev-id">{device.id}</code>
                    </td>
                    <td className="col-paired">{paired ? dayMonthYear(paired, tr.locale) : MISSING}</td>
                    <td className="col-seen" title={seen.title}>{seen.text}</td>
                    <td className="col-term">{granted?.has(device.id) ? <Tag tone="ok">{t("granted")}</Tag> : MISSING}</td>
                    <td>
                      <ActionButton
                        variant="outline-danger"
                        small
                        disabled={revoke.working}
                        onClick={() => setAsking(device)}
                        aria-label={`${t("revoke")}… ${device.name}`}
                      >
                        {t("revoke")}…
                      </ActionButton>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {guard.reasonKey ? <p className="reason-hint">{t(guard.reasonKey)}</p> : null}
        </>
      )}
      {asking ? (
        <ConfirmDialog
          title={t("d_revoke_t", { name: asking.name })}
          confirmLabel={t("revoke")}
          onConfirm={confirmRevoke}
          onCancel={() => setAsking(null)}
        >
          <p>{t("d_revoke_b")}</p>
        </ConfirmDialog>
      ) : null}
    </>
  );
}
