import type { ReactNode } from "react";
import { useI18n } from "../i18n";
import { useSnapshot } from "../state/snapshot";
import { useRoute } from "../state/route";
import { Banner } from "./Banner";
import { SetupState } from "./SetupState";
import { Sidebar } from "./Sidebar";

/**
 * Shell 2 (design.md 3.1): grouped sidebar with the status footer, the mismatch banner above every
 * view, and the full "not installed" / "no CLI" states in place of a view.
 */
export function Shell({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const { world } = useSnapshot();
  const [view, open] = useRoute();
  const setup = world.row === 1 || world.row === 2;
  return (
    <div className="shell">
      <Sidebar current={view} onOpen={open} />
      <main>
        {world.mismatch ? (
          <div className="banner-slot">
            <Banner role="alert" light="warn" title={t("mm_title")}>
              {t("mm_body", { srv: world.mismatch.server, app: world.mismatch.desktop })}
            </Banner>
          </div>
        ) : null}
        <div className="content">{setup ? <SetupState world={world} /> : children}</div>
      </main>
    </div>
  );
}
