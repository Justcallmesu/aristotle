import { useEffect, useRef, useState } from "react";
import { useI18n } from "../i18n";
import { Button } from "./Button";

const FEEDBACK_MS = 1200;

/** Copies `text`; the label becomes "Copied" for 1.2 s inside a polite live region (design.md 3.3). */
export function CopyButton({ text }: { text: string }) {
  const { t } = useI18n();
  const [copied, setCopied] = useState(false);
  const timer = useRef<number>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      return;
    }
    setCopied(true);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setCopied(false), FEEDBACK_MS);
  }

  return (
    <span aria-live="polite">
      <Button variant="tonal" small onClick={copy}>
        {copied ? t("copied") : t("copy")}
      </Button>
    </span>
  );
}
