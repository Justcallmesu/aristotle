import { useI18n } from "../i18n";
import type { IpcError } from "../ipc";
import { Banner } from "./Banner";
import { CodeBlock } from "./CodeBlock";

/**
 * design.md 4.2 "command failed": an alert right after the view header, above the content. Views render
 * it there themselves; the banner has no outer margin of its own beyond the gap below it. The server's own text goes in a
 * code block (shown, never logged); with no text the error kind stands in.
 */
export function CommandFailed({ error }: { error: IpcError }) {
  const { t, word } = useI18n();
  // A resolved `{ ok: false, service }` carries the raw §22 word: shown through the catalog, raw when missing.
  const text = error.kind === "not-ok" && error.message ? word("svc_", error.message) : (error.message ?? error.kind);
  return (
    <Banner role="alert" light="err" title={t("act_failed_t")}>
      <p>{t("act_failed_b")}</p>
      <CodeBlock>{`error: ${text}`}</CodeBlock>
    </Banner>
  );
}
