interface ProgressProps {
  label: string;
  /** Omit for the indeterminate bar of a running Start / Stop / Restart. */
  value?: number;
  max?: number;
}

export function Progress({ label, value, max = 100 }: ProgressProps) {
  const determinate = value !== undefined;
  return (
    <div
      className="progress"
      role="progressbar"
      aria-label={label}
      aria-valuemin={determinate ? 0 : undefined}
      aria-valuemax={determinate ? max : undefined}
      aria-valuenow={determinate ? value : undefined}
    >
      <div
        className={determinate ? "progress-fill" : "progress-fill progress-indeterminate"}
        style={determinate ? { width: `${Math.max(0, Math.min(1, value / max)) * 100}%` } : undefined}
      />
    </div>
  );
}
