import { useI18n } from "../i18n";
import type { IpcError } from "../ipc";
import { Banner } from "./Banner";
import { CodeBlock } from "./CodeBlock";

/**
 * design.md 4.2 "command failed": an alert above the view's content. The server's own text goes in a
 * code block (shown, never logged); with no text the error kind stands in.
 */
export function CommandFailed({ error }: { error: IpcError }) {
  const { t } = useI18n();
  return (
    <div className="banner-slot">
      <Banner role="alert" light="err" title={t("act_failed_t")}>
        <p>{t("act_failed_b")}</p>
        <CodeBlock>{`error: ${error.message ?? error.kind}`}</CodeBlock>
      </Banner>
    </div>
  );
}
