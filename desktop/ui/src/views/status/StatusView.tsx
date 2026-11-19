import { ActionButton, Button } from "../../components/Button";
import { CodeBlock } from "../../components/CodeBlock";
import { CommandFailed } from "../../components/CommandFailed";
import { CopyButton } from "../../components/CopyButton";
import { Progress } from "../../components/Progress";
import { StateLight } from "../../components/StateLight";
import { KvGroup, KvRow, StateRow } from "../../components/Rows";
import { Tag } from "../../components/Tag";
import { ViewHeader } from "../../components/ViewHeader";
import { DASH } from "../../format/dash";
import { relativeText } from "../../format/time";
import { useI18n, type Translator } from "../../i18n";
import { type Address, type Listener, type SnapshotDto, type Status } from "../../ipc";
import { enabledWord, latestSeen, listenAddress, serviceWord, statusOf } from "../../state/facts";
import { navigate } from "../../state/route";
import { canStart } from "../../state/serviceButtons";
import { useSnapshot } from "../../state/snapshot";
import { useServiceAction } from "../../state/useServiceAction";
import type { World } from "../../state/world";
import "./status.css";

export function StatusView() {
  const { t } = useI18n();
  const { snapshot, world } = useSnapshot();
  const status = statusOf(snapshot);
  const start = useServiceAction();
  const startable = canStart(status, world);

  return (
    <>
      <ViewHeader title={t("nav_status")} srOnly />
      {start.error ? <CommandFailed error={start.error} /> : null}
      <StateRow
        busy={world.row === 0}
        light={world.light ? <StateLight light={world.light} /> : null}
        title={t(world.titleKey)}
        sub={world.subKey ? t(world.subKey, world.subParams) : undefined}
        action={
          startable ? (
            <ActionButton variant="primary" disabled={start.busy} onClick={() => void start.run("start")}>
              {t("b_start")}
            </ActionButton>
          ) : undefined
        }
      />
      {start.busy ? <Working label={t("working_start")} /> : null}
      {world.row === 0 ? null : (
        <>
          {world.row === 2 ? <InstallCommand /> : null}
          <div className="columns">
            <ServerGroup status={status} world={world} />
            <div>
              <ListenGroup status={status} />
              {world.running ? <PhonesGroup snapshot={snapshot} /> : null}
            </div>
          </div>
        </>
      )}
    </>
  );
}

function Working({ label }: { label: string }) {
  return (
    <div className="status-working">
      <Progress label={label} />
      <p className="hint">{label}</p>
    </div>
  );
}

function InstallCommand() {
  const { t } = useI18n();
  return (
    <div className="status-install">
      <h2 className="group-title">{t("install_cmd")}</h2>
      <div className="setup-cmd">
        <CodeBlock>./install.sh</CodeBlock>
        <CopyButton text="./install.sh" />
      </div>
    </div>
  );
}

function ServerGroup({ status, world }: { status: Status | null; world: World }) {
  const tr = useI18n();
  const { t } = tr;
  const result = status?.result ?? null;
  return (
    <KvGroup title={t("g_server")}>
      <KvRow label={t("k_service")}>{serviceWord(tr, status)}</KvRow>
      <KvRow label={t("k_enabled")}>{enabledWord(tr, status)}</KvRow>
      <KvRow label={t("k_version")}>
        {result?.version ?? DASH}
        {world.mismatch ? <Tag tone="err">{t("app_expects", { n: world.mismatch.desktop })}</Tag> : null}
      </KvRow>
      <KvRow label={t("k_port")}>
        <span className="mono tnum">{result?.port ?? DASH}</span>
      </KvRow>
      <KvRow label={t("k_hermes")}>
        <HermesValue result={result} running={world.running} tr={tr} />
      </KvRow>
    </KvGroup>
  );
}

function HermesValue({ result, running, tr }: { result: Status["result"]; running: boolean; tr: Translator }) {
  if (!running) return <>{tr.t("hs_none")}</>;
  const hermes = result?.hermes ?? null;
  const word = hermes ? tr.word("hs_", hermes.state) : tr.t("hs_unknown");
  return (
    <>
      <span className="kv-val-light">
        <StateLight light={hermes?.state === "reachable" ? "ok" : "warn"} />
        {word}
      </span>
      {hermes?.reason ? <div className="kv-sub">{hermes.reason}</div> : null}
    </>
  );
}

/** `default_route` is not in §22's status addresses today; read it when a server sends it. */
function isDefaultRoute(addresses: Address[], iface: string): boolean {
  return addresses.some((a) => a.iface === iface && (a as Address & { default_route?: boolean }).default_route === true);
}

function ListenGroup({ status }: { status: Status | null }) {
  const tr = useI18n();
  const { t } = tr;
  const result = status?.result ?? null;
  const listeners = result?.listeners ?? [];
  return (
    <KvGroup title={t("g_listen")} listen>
      {listeners.length === 0 ? (
        <KvRow label="">{t(result ? "no_listen_up" : "no_listen")}</KvRow>
      ) : (
        listeners.map((l) => (
          <ListenerRow key={`${l.kind}-${l.iface}-${l.ip}`} listener={l} port={result?.port ?? null} addresses={result?.addresses ?? []} />
        ))
      )}
    </KvGroup>
  );
}

function ListenerRow({ listener, port, addresses }: { listener: Listener; port: number | null; addresses: Address[] }) {
  const tr = useI18n();
  const detail = [listener.iface, listener.subnet, isDefaultRoute(addresses, listener.iface) ? tr.t("default_route") : null]
    .filter(Boolean)
    .join(" · ");
  return (
    <KvRow label={tr.word("kind_", listener.kind)}>
      <div className="mono tnum kv-nowrap">{listenAddress(listener.ip, port)}</div>
      <div className="kv-sub">{detail}</div>
    </KvRow>
  );
}

function PhonesGroup({ snapshot }: { snapshot: SnapshotDto | null }) {
  const tr = useI18n();
  const { t } = tr;
  const devices = snapshot?.devices ?? null;
  const enabled = snapshot?.terminal_enabled ?? null;
  const open = snapshot?.terminals?.length ?? null;
  const granted = snapshot?.terminal_granted?.length ?? null;
  const latest = devices ? latestSeen(devices) : null;
  return (
    <KvGroup title={t("g_phones")}>
      <KvRow
        label={t("k_paired")}
        end={
          <Button variant="text" small onClick={() => navigate("devices")}>
            {t("open_devices")}
          </Button>
        }
      >
        {devices === null ? DASH : latest ? t("paired_summary", { n: devices.length, t: relativeText(tr, latest.last_seen_at) }) : t("none_paired")}
      </KvRow>
      <KvRow
        label={t("k_terminal")}
        end={
          <Button variant="text" small onClick={() => navigate("terminal")}>
            {t("open_terminal")}
          </Button>
        }
      >
        {enabled === null ? DASH : enabled ? t("term_summary_on", { open: open ?? DASH, granted: granted ?? DASH }) : t("term_summary_off")}
      </KvRow>
    </KvGroup>
  );
}
