import { useI18n } from "../i18n";
import { footerDetail, footerState } from "../state/world";
import { useSnapshot } from "../state/snapshot";
import { StateLight } from "./StateLight";

/** Same on every view; the live state in words (design.md 3.1). */
export function StatusFooter() {
  const tr = useI18n();
  const { snapshot, world } = useSnapshot();
  const { light, titleKey } = footerState(world);
  return (
    <div className="side-footer" role="status">
      <div className="side-footer-state">
        {light ? <StateLight light={light} size={12} /> : null}
        <span>{tr.t(titleKey)}</span>
      </div>
      <div className="side-footer-detail">{footerDetail(snapshot, tr)}</div>
    </div>
  );
}
