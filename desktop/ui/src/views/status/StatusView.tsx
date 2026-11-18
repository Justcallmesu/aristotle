import { ViewHeader } from "../../components/ViewHeader";
import { useI18n } from "../../i18n";

/** Empty placeholder: the view itself is built in its own ticket (DS4-DS6). */
export function StatusView() {
  const { t } = useI18n();
  return <ViewHeader title={t("nav_status")} srOnly />;
}
