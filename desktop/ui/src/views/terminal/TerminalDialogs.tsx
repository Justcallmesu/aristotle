import { ConfirmDialog, WarnBox } from "../../components/ConfirmDialog";
import { useI18n } from "../../i18n";

export type TerminalDialog =
  | { kind: "enable" }
  | { kind: "grant"; id: string; name: string }
  /** `open` is the count read when the switch was flipped (D19-8). */
  | { kind: "off"; open: number };

interface Props {
  dialog: TerminalDialog;
  /** `granted` is empty: the enable dialog adds the "no phone holds a grant" line (design.md 3.6). */
  noGrants: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/** The three terminal dialogs: D7 enable, D7 grant, and turn off (D19-8). No remember option on any. */
export function TerminalDialogs({ dialog, noGrants, onConfirm, onCancel }: Props) {
  const { t } = useI18n();
  if (dialog.kind === "off") {
    return (
      <ConfirmDialog title={t("d_toff_t")} confirmLabel={t("t_turn_off")} onConfirm={onConfirm} onCancel={onCancel}>
        <p>{t("d_toff_b", { n: dialog.open })}</p>
      </ConfirmDialog>
    );
  }
  if (dialog.kind === "grant") {
    return (
      <ConfirmDialog
        title={t("d7_grant_t", { name: dialog.name })}
        confirmLabel={t("d7_grant_b")}
        onConfirm={onConfirm}
        onCancel={onCancel}
      >
        <WarnBox>{t("d7_warn")}</WarnBox>
      </ConfirmDialog>
    );
  }
  return (
    <ConfirmDialog title={t("d7_enable_t")} confirmLabel={t("d7_enable_b")} onConfirm={onConfirm} onCancel={onCancel}>
      <WarnBox>{t("d7_warn")}</WarnBox>
      {noGrants ? <p>{t("d7_nogrant")}</p> : null}
    </ConfirmDialog>
  );
}
