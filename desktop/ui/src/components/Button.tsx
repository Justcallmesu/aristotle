import type { ComponentProps } from "react";
import { useActionGuard } from "../state/snapshot";

export type ButtonVariant =
  | "primary"
  | "tonal"
  | "default"
  | "outline"
  | "outline-danger"
  | "danger"
  | "text"
  | "text-danger";

export interface ButtonProps extends ComponentProps<"button"> {
  variant?: ButtonVariant;
  small?: boolean;
}

export function Button({ variant = "default", small = false, className, type = "button", ...rest }: ButtonProps) {
  const classes = ["btn", `btn-${variant}`, small ? "btn-small" : "", className ?? ""];
  return <button type={type} className={classes.filter(Boolean).join(" ")} {...rest} />;
}

/** A button that changes the server: off while the server and the app disagree (P5). */
export function ActionButton({ disabled, ...rest }: ButtonProps) {
  const guard = useActionGuard();
  return <Button {...rest} disabled={disabled || guard.disabled} />;
}
