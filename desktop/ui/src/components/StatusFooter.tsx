import { useI18n } from "../i18n";
import { footerDetail } from "../state/world";
import { useSnapshot } from "../state/snapshot";
import { StateLight } from "./StateLight";

/** Same on every view; the live state in words (design.md 3.1). */
export function StatusFooter() {
  const tr = useI18n();
  const { snapshot, world } = useSnapshot();
  return (
    <div className="side-footer" role="status">
      <div className="side-footer-state">
        {world.light ? <StateLight light={world.light} size={12} /> : null}
        <span>{tr.t(world.titleKey)}</span>
      </div>
      <div className="side-footer-detail">{footerDetail(snapshot, tr)}</div>
    </div>
  );
}
