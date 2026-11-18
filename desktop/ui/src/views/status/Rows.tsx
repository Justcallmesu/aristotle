import { useId, type ReactNode } from "react";
import { CommandFailed } from "../../components/CommandFailed";
import type { IpcError } from "../../ipc";
import "./rows.css";

/** design.md 3.5 grouped rows: an optional header and rows 2 px apart. */
export function KvGroup({ title, listen = false, children }: { title?: string; listen?: boolean; children: ReactNode }) {
  const titleId = useId();
  return (
    <div className={listen ? "kv-group kv-listen" : "kv-group"} role="group" aria-labelledby={title ? titleId : undefined}>
      {title ? (
        <h2 id={titleId} className="group-title">
          {title}
        </h2>
      ) : null}
      <div className="kv-rows">{children}</div>
    </div>
  );
}

export function KvRow({ label, end, children }: { label: string; end?: ReactNode; children: ReactNode }) {
  return (
    <div className="kv-row">
      {label ? <span className="kv-key">{label}</span> : null}
      <div className="kv-val">{children}</div>
      {end ? <div className="kv-end">{end}</div> : null}
    </div>
  );
}

/** design.md 3.5 state row: the headline with its light, support text, an optional action at the end. */
export function StateRow({ title, sub, light, action, busy = false }: { title: ReactNode; sub?: string; light?: ReactNode; action?: ReactNode; busy?: boolean }) {
  return (
    <section className="kv-state" aria-busy={busy || undefined}>
      <div className="kv-state-text">
        <h2 className="state-title">
          {light}
          {title}
        </h2>
        {sub ? <p className="kv-state-sub">{sub}</p> : null}
      </div>
      {action ? <div className="kv-state-action">{action}</div> : null}
    </section>
  );
}

/** The shared failure banner sits inside the view's padding here, so it drops its own outer margin. */
export function FailedBanner({ error }: { error: IpcError }) {
  return (
    <div className="kv-failed">
      <CommandFailed error={error} />
    </div>
  );
}
