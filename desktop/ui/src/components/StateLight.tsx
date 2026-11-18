import type { Light } from "../state/world";

const shapes: Record<Light, React.ReactNode> = {
  ok: <circle cx="6" cy="6" r="5" />,
  warn: <path d="M6 .8 11.4 10.6H.6Z" />,
  err: <rect x=".8" y=".8" width="10.4" height="10.4" rx="1.5" />,
};

/** design.md 3.2: a shape that always sits beside a word, so colour is never the only signal. */
export function StateLight({ light, size = 14 }: { light: Light; size?: 10 | 12 | 14 }) {
  return (
    <svg
      className={`light light-${light}`}
      width={size}
      height={size}
      viewBox="0 0 12 12"
      aria-hidden="true"
      focusable="false"
    >
      {shapes[light]}
    </svg>
  );
}
