import { useCallback, useEffect, useLayoutEffect, useReducer, useRef, useState } from "react";
import { Button } from "../../components/Button";
import { Switch } from "../../components/Switch";
import { ViewHeader } from "../../components/ViewHeader";
import { StateLight } from "../../components/StateLight";
import { useI18n } from "../../i18n";
import { useIpc } from "../../ipc";
import { useSnapshot } from "../../state/snapshot";
import { appendLines, EMPTY_BUFFER, LOG_CAP, type LogBuffer } from "./logBuffer";
import { LogLine } from "./logLine";
import "./logs.css";

/** Pixels from the bottom that still count as "at the newest line". */
const BOTTOM_SLACK = 8;

type Action = { type: "lines"; lines: string[] } | { type: "reset" };

function reducer(buffer: LogBuffer, action: Action): LogBuffer {
  return action.type === "reset" ? EMPTY_BUFFER : appendLines(buffer, action.lines);
}

export function LogsView() {
  const { t } = useI18n();
  const { world } = useSnapshot();
  const [buffer, dispatch] = useReducer(reducer, EMPTY_BUFFER);
  const { ended, reconnect } = useLogStream((lines) => dispatch({ type: "lines", lines }), () => dispatch({ type: "reset" }));
  const [follow, setFollow] = useState(true);
  const [wrap, setWrap] = useState(true);
  const [missed, setMissed] = useState(0);
  const box = useRef<HTMLDivElement>(null);
  const seen = useRef(0);

  // Lines that arrive while Follow is off are counted for the "N new lines" status.
  useEffect(() => {
    const total = buffer.nextId;
    const arrived = total - seen.current;
    seen.current = total;
    if (!follow && arrived > 0) setMissed((n) => n + arrived);
  }, [buffer.nextId, follow]);

  useLayoutEffect(() => {
    const el = box.current;
    if (follow && el) el.scrollTop = el.scrollHeight;
  }, [buffer, follow, wrap]);

  const changeFollow = useCallback((next: boolean) => {
    setFollow(next);
    if (next) setMissed(0);
  }, []);

  function onScroll() {
    const el = box.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_SLACK;
    if (atBottom && !follow) changeFollow(true);
    else if (!atBottom && follow) changeFollow(false);
  }

  return (
    <>
      <ViewHeader title={t("logs_title")} subtitle={t("logs_sub")} />
      <div className="log-bar">
        <Switch checked={follow} onChange={changeFollow} label={t("follow")} disabled={!world.running} />
        <Switch checked={wrap} onChange={setWrap} label={t("wrap")} />
        {world.running ? <FollowStatus follow={follow} missed={missed} /> : null}
        {ended ? (
          <div className="log-ended" role="alert">
            <span>{t("logs_ended")}</span>
            <Button variant="tonal" small onClick={reconnect}>
              {t("logs_retry")}
            </Button>
          </div>
        ) : null}
      </div>
      <div className="log-wrap">
        <div
          ref={box}
          className={wrap ? "log-box" : "log-box log-nowrap"}
          role="log"
          tabIndex={0}
          aria-live="off"
          aria-label={t("logs_title")}
          onScroll={onScroll}
        >
          {buffer.entries.map((entry) => (
            <LogLine key={entry.id} text={entry.text} />
          ))}
        </div>
        {!follow && world.running ? (
          <Button className="log-jump" variant="primary" small onClick={() => changeFollow(true)}>
            {t("jump")}
          </Button>
        ) : null}
      </div>
      {world.running ? null : <p className="log-note">{t("logs_stopped")}</p>}
      <p className="log-note">{t("cap_note", { n: LOG_CAP.toLocaleString("en-US") })}</p>
    </>
  );
}

function FollowStatus({ follow, missed }: { follow: boolean; missed: number }) {
  const { t } = useI18n();
  if (follow) {
    return (
      <span className="log-live">
        <StateLight light="ok" size={10} />
        {t("live")}
      </span>
    );
  }
  return <span className="log-paused">{t("paused", { n: missed })}</span>;
}

/**
 * `logs_start` when the view opens, `logs_stop` when it closes (design.md 4.5, P9). The line and
 * end events are subscribed before the stream starts so no first batch is missed. A stream that
 * ends or fails to start sets `ended`; `reconnect` drops the old lines (the journal replays its
 * tail) and starts again.
 */
function useLogStream(onLines: (lines: string[]) => void, onReset: () => void) {
  const client = useIpc();
  const [ended, setEnded] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const handlers = useRef({ onLines, onReset });
  handlers.current = { onLines, onReset };

  useEffect(() => {
    let alive = true;
    const offs: (() => void)[] = [];
    const keep = (off: () => void) => (alive ? offs.push(off) : off());
    async function open() {
      keep(await client.on("logs-lines", (payload) => alive && handlers.current.onLines(payload.lines)));
      keep(await client.on("logs-ended", () => alive && setEnded(true)));
      if (!alive) return;
      try {
        await client.logsStart();
      } catch {
        if (alive) setEnded(true);
      }
    }
    void open();
    return () => {
      alive = false;
      offs.forEach((off) => off());
      client.logsStop().catch(() => undefined);
    };
  }, [client, attempt]);

  const reconnect = useCallback(() => {
    handlers.current.onReset();
    setEnded(false);
    setAttempt((n) => n + 1);
  }, []);

  return { ended, reconnect };
}
