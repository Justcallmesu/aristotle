import type { ReactNode } from "react";
import type { Light } from "../state/world";
import { StateLight } from "./StateLight";

interface BannerProps {
  /** `alert` for blocking problems, `status` for warnings (design.md 3.4). */
  role: "alert" | "status";
  light: Light;
  title: ReactNode;
  children?: ReactNode;
  actions?: ReactNode;
}

export function Banner({ role, light, title, children, actions }: BannerProps) {
  return (
    <div className="banner" role={role}>
      <StateLight light={light} />
      <div className="banner-text">
        <strong>{title}</strong>
        {children ? <div>{children}</div> : null}
      </div>
      {actions ? <div className="banner-actions">{actions}</div> : null}
    </div>
  );
}
