import { I18nProvider } from "./i18n";
import { Shell } from "./components/Shell";
import { IntentProvider } from "./state/intent";
import { useRoute } from "./state/route";
import { SnapshotProvider } from "./state/snapshot";
import { viewComponents } from "./views";

function CurrentView() {
  const [view] = useRoute();
  const View = viewComponents[view];
  return <View />;
}

export function App() {
  return (
    <I18nProvider>
      <SnapshotProvider>
        <IntentProvider>
          <Shell>
            <CurrentView />
          </Shell>
        </IntentProvider>
      </SnapshotProvider>
    </I18nProvider>
  );
}
