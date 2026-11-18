import { useEffect, useId, useRef, type ReactNode } from "react";
import { useI18n } from "../i18n";
import { Button } from "./Button";

interface ConfirmDialogProps {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  /** `danger` is the filled red confirm (Stop, Revoke, D7, Turn off); `primary` for Restart. */
  confirmVariant?: "danger" | "primary";
  onConfirm: () => void;
  onCancel: () => void;
}

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Mount it to open it, unmount to close (design.md 3.6, D19-9): focus starts on Cancel, Tab stays
 * inside, Esc / scrim click / Cancel close and send nothing, focus returns to the opener.
 */
export function ConfirmDialog({
  title,
  children,
  confirmLabel,
  confirmVariant = "danger",
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const { t } = useI18n();
  const titleId = useId();
  const bodyId = useId();
  const box = useRef<HTMLDivElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const done = useRef(false);
  const callbacks = useRef({ onCancel });
  callbacks.current = { onCancel };

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    cancel.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        callbacks.current.onCancel();
      } else if (event.key === "Tab") {
        trapTab(event, box.current);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      opener?.focus();
    };
  }, []);

  function confirm() {
    if (done.current) return;
    done.current = true;
    onConfirm();
  }

  return (
    <div className="scrim" onClick={onCancel}>
      <div
        ref={box}
        className="dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={bodyId}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id={titleId}>{title}</h2>
        <div id={bodyId} className="dialog-body">
          {children}
        </div>
        <div className="dialog-actions">
          <Button ref={cancel} variant="outline" onClick={onCancel}>
            {t("b_cancel")}
          </Button>
          <Button variant={confirmVariant} onClick={confirm}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}

function trapTab(event: KeyboardEvent, root: HTMLElement | null) {
  const items = root ? Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)) : [];
  if (items.length === 0) return;
  const first = items[0];
  const last = items[items.length - 1];
  const active = document.activeElement;
  if (!root?.contains(active)) {
    event.preventDefault();
    first.focus();
  } else if (event.shiftKey && active === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && active === last) {
    event.preventDefault();
    first.focus();
  }
}

export function WarnBox({ children }: { children: ReactNode }) {
  return <p className="warn-box">{children}</p>;
}
