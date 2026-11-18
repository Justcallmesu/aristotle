import type { ReactNode } from "react";

interface ViewHeaderProps {
  title: string;
  subtitle?: string;
  actions?: ReactNode;
  /** Status has only a screen-reader-only h1 (design.md 4.3). */
  srOnly?: boolean;
}

export function ViewHeader({ title, subtitle, actions, srOnly = false }: ViewHeaderProps) {
  if (srOnly) return <h1 className="sr-only">{title}</h1>;
  return (
    <header className="view-header">
      <div>
        <h1>{title}</h1>
        {subtitle ? <p className="view-sub">{subtitle}</p> : null}
      </div>
      {actions ? <div className="view-actions">{actions}</div> : null}
    </header>
  );
}
