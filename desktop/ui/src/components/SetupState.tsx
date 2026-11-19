import { useI18n } from "../i18n";
import type { World } from "../state/world";
import { CodeBlock } from "./CodeBlock";
import { CopyButton } from "./CopyButton";
import { StateLight } from "./StateLight";

/**
 * design.md 4.1 rows 1-2: the CLI cannot be run, or the unit is not installed. Nothing here can be
 * fixed from the app (P3): the one action is to run the installer, so there is no install button.
 */
export function SetupState({ world }: { world: World }) {
  const { t } = useI18n();
  return (
    <section className="empty setup" aria-labelledby="setup-title">
      <h1 id="setup-title" className="state-title">
        {world.light ? <StateLight light={world.light} /> : null}
        {t(world.titleKey)}
      </h1>
      {world.subKey ? <p>{t(world.subKey, world.subParams)}</p> : null}
      {world.row === 2 ? (
        <div className="setup-install">
          <h2 className="group-title">{t("install_cmd")}</h2>
          <div className="setup-cmd">
            <CodeBlock>./install.sh</CodeBlock>
            <CopyButton text="./install.sh" />
          </div>
        </div>
      ) : null}
    </section>
  );
}
