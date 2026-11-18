import { ViewHeader } from "../../components/ViewHeader";
import { useI18n } from "../../i18n";

/** Empty placeholder: the view itself is built in its own ticket (DS4-DS6). */
export function DevicesView() {
  const { t } = useI18n();
  return <ViewHeader title={t("dev_title")} subtitle={t("dev_sub")} />;
}
