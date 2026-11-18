import { useId } from "react";

interface SwitchProps {
  checked: boolean;
  onChange: (next: boolean) => void;
  label: string;
  disabled?: boolean;
}

/** design.md 3.3: a `role="switch"` button with its text label beside it, label clickable. */
export function Switch({ checked, onChange, label, disabled = false }: SwitchProps) {
  const labelId = useId();
  const toggle = () => !disabled && onChange(!checked);
  return (
    <span className="switch-wrap">
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-labelledby={labelId}
        aria-disabled={disabled || undefined}
        className="switch"
        onClick={toggle}
      >
        <span className="switch-thumb" />
      </button>
      <span id={labelId} className="switch-label" onClick={toggle}>
        {label}
      </span>
    </span>
  );
}
