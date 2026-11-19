import { useState } from "react";
import { ActionButton, Button } from "../../components/Button";
import { CommandFailed } from "../../components/CommandFailed";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { EmptyState } from "../../components/EmptyState";
import { NeedsServer } from "../../components/NeedsServer";
import { Tag } from "../../components/Tag";
import { ViewHeader } from "../../components/ViewHeader";
import { DASH } from "../../format/dash";
import { dmyText, relativeText } from "../../format/time";
import { useI18n } from "../../i18n";
import { useIpc, type Device } from "../../ipc";
import { useRoute } from "../../state/route";
import { useActionGuard, useSnapshot } from "../../state/snapshot";
import { useCommand } from "../../state/useCommand";
import "./devices.css";

export function DevicesView() {
  const { t } = useI18n();
  const { snapshot, world } = useSnapshot();
  const granted = snapshot?.terminal_granted ?? null;
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
  return <DeviceList devices={snapshot.devices} granted={granted} />;
}

function DeviceList({ devices, granted }: { devices: Device[] | null; granted: string[] | null }) {
  const tr = useI18n();
  const { t } = tr;
  const client = useIpc();
  const [, navigate] = useRoute();
  const guard = useActionGuard();
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
      <ViewHeader title={t("dev_title")} subtitle={t("dev_sub")} actions={pairButton("tonal")} />
      {revoke.error ? <CommandFailed error={revoke.error} /> : null}
      {devices === null ? (
        <p>{DASH}</p>
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
                return (
                  <tr key={device.id}>
                    <td>
                      <span className="dev-name">{device.name}</span>
                      <code className="dev-id">{device.id}</code>
                    </td>
                    <td className="col-paired">{dmyText(tr, device.created_at)}</td>
                    <td className="col-seen" title={device.last_seen_at}>{relativeText(tr, device.last_seen_at)}</td>
                    <td className="col-term">{granted?.includes(device.id) ? <Tag tone="ok">{t("granted")}</Tag> : DASH}</td>
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
          {guard.reasonKey ? <p className="hint">{t(guard.reasonKey)}</p> : null}
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
