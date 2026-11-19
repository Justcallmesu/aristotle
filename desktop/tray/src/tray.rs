//! The ksni tray object: icon, tooltip, menu, click handling.

use crate::icons;
use crate::launcher::{Launch, Launcher};
use crate::menu::{self, Entry, MenuInput};
use crate::notifier;
use crate::offline::{self, SharedOfflineWatch};
use crate::starting::Starting;
use aristotle_core::runner::{query, ServerCli};
use aristotle_core::snapshot::Snapshot;
use aristotle_core::state::{assess, Assessment, Reason, TrayState};
use aristotle_core::strings::{text, tooltip, Key, Lang, APP_NAME};
use aristotle_core::types::ActionResult;
use ksni::menu::StandardItem;
use ksni::{Icon, MenuItem, OfflineReason, ToolTip, Tray};
use std::sync::Arc;
use std::thread;
use std::time::Instant;

pub struct AristotleTray {
    pub assessment: Assessment,
    pub menu_input: MenuInput,
    pub lang: Lang,
    pub launcher: Arc<Launcher>,
    pub cli: Arc<dyn ServerCli>,
    starting: Starting,
    offline: SharedOfflineWatch,
}

impl AristotleTray {
    pub fn new(
        snapshot: &Snapshot,
        lang: Lang,
        launcher: Arc<Launcher>,
        cli: Arc<dyn ServerCli>,
        offline: SharedOfflineWatch,
    ) -> Self {
        let mut tray = Self {
            assessment: Assessment {
                state: TrayState::Red,
                reason: Reason::NoAnswer,
            },
            menu_input: MenuInput::none(),
            lang,
            launcher,
            cli,
            starting: Starting::default(),
            offline,
        };
        tray.apply(snapshot);
        tray
    }

    /// Takes a new snapshot. Returns true when anything the tray draws changed.
    pub fn apply(&mut self, snapshot: &Snapshot) -> bool {
        let next = (assess(snapshot), MenuInput::from_snapshot(snapshot));
        let changed = next != (self.assessment, self.menu_input);
        (self.assessment, self.menu_input) = next;
        changed
    }

    fn open(&self, args: &[&str]) {
        // Wayland focus: the host hands a token with the click (None when launched from a menu timer).
        let token = ksni::current_xdg_activation_token();
        match self.launcher.open(args, token) {
            Launch::Missing => notifier::show_window_missing(self.lang),
            Launch::Failed => eprintln!("aristotle-tray: could not start the window app"),
            Launch::Started(_) | Launch::AlreadyRunning(_) => {}
        }
    }

    fn start_server(&self) {
        // One start at a time; the entry is disabled meanwhile (see `item`).
        let Some(token) = self.starting.try_begin() else {
            return;
        };
        let cli = Arc::clone(&self.cli);
        thread::spawn(move || {
            match query::<ActionResult>(cli.as_ref(), &["start"]) {
                // P11: the app never logs CLI output, so only a fixed reason code is written.
                Ok(_) => eprintln!("aristotle-tray: start ok"),
                Err(e) => eprintln!("aristotle-tray: start failed: {}", e.kind()),
            }
            drop(token);
        });
    }

    fn item(&self, entry: Entry) -> MenuItem<Self> {
        let l = self.lang;
        let label = |key| text(l, key).to_owned();
        match entry {
            Entry::Open => StandardItem {
                label: label(Key::MenuOpen),
                activate: Box::new(|t: &mut Self| t.open(&[])),
                ..Default::default()
            },
            Entry::Pair { enabled } => StandardItem {
                label: label(Key::MenuPair),
                enabled,
                activate: Box::new(|t: &mut Self| t.open(&["--pair"])),
                ..Default::default()
            },
            Entry::Start => StandardItem {
                label: label(Key::MenuStart),
                enabled: !self.starting.active(),
                activate: Box::new(|t: &mut Self| t.start_server()),
                ..Default::default()
            },
            Entry::Stop => StandardItem {
                label: label(Key::MenuStop),
                activate: Box::new(|t: &mut Self| t.open(&["--confirm", "stop"])),
                ..Default::default()
            },
            // Quit leaves the tray only; the server keeps running (D4).
            Entry::Quit => StandardItem {
                label: label(Key::MenuQuit),
                activate: Box::new(|_| std::process::exit(0)),
                ..Default::default()
            },
        }
        .into()
    }
}

impl Tray for AristotleTray {
    fn id(&self) -> String {
        "aristotle-tray".into()
    }

    fn title(&self) -> String {
        APP_NAME.into()
    }

    fn icon_pixmap(&self) -> Vec<Icon> {
        icons::pixmaps(self.assessment.state).to_vec()
    }

    fn tool_tip(&self) -> ToolTip {
        ToolTip {
            title: APP_NAME.into(),
            description: tooltip(self.lang, self.assessment.reason).into(),
            ..Default::default()
        }
    }

    fn activate(&mut self, _x: i32, _y: i32) {
        self.open(&[]);
    }

    fn menu(&self) -> Vec<MenuItem<Self>> {
        let plan = menu::plan(&self.menu_input);
        let mut items = Vec::new();
        for entry in plan {
            // Separators as drawn on the board: before Start/Stop and before Quit.
            if matches!(entry, Entry::Start | Entry::Stop | Entry::Quit) {
                items.push(MenuItem::Separator);
            }
            items.push(self.item(entry));
        }
        items
    }

    fn watcher_online(&self) {
        offline::lock(&self.offline).came_online();
        eprintln!("aristotle-tray: watcher online");
    }

    fn watcher_offline(&self, reason: OfflineReason) -> bool {
        offline::lock(&self.offline).went_offline(Instant::now());
        eprintln!("aristotle-tray: watcher offline ({reason:?}), waiting");
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aristotle_core::runner::{CliError, RawOutput};
    use aristotle_core::snapshot::{StatusPoll, VersionCheck};
    use aristotle_core::types::{ServerState, Status};
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::{mpsc, Mutex};
    use std::time::{Duration, Instant};

    /// A CLI whose `run` blocks until the test sends the reply, and counts its calls.
    struct BlockedCli {
        calls: AtomicUsize,
        release: Mutex<mpsc::Receiver<Result<RawOutput, CliError>>>,
    }

    impl ServerCli for BlockedCli {
        fn run(&self, _args: &[&str]) -> Result<RawOutput, CliError> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            self.release.lock().unwrap().recv().unwrap()
        }
    }

    fn stopped() -> Snapshot {
        Snapshot {
            status: StatusPoll::Answered(Box::new(Status {
                service: "inactive".into(),
                enabled: "enabled".into(),
                installed: true,
                server: ServerState::NotRunning,
                result: None,
            })),
            devices: None,
            terminals: None,
            terminal_enabled: None,
            terminal_granted: None,
            version: VersionCheck::Skipped,
        }
    }

    fn tray_with_blocked_cli() -> (
        AristotleTray,
        Arc<BlockedCli>,
        mpsc::Sender<Result<RawOutput, CliError>>,
    ) {
        let (tx, rx) = mpsc::channel();
        let cli = Arc::new(BlockedCli {
            calls: AtomicUsize::new(0),
            release: Mutex::new(rx),
        });
        let tray = AristotleTray::new(
            &stopped(),
            Lang::from_env(),
            Launcher::from_env(|| {}),
            cli.clone(),
            SharedOfflineWatch::default(),
        );
        (tray, cli, tx)
    }

    fn start_enabled(tray: &AristotleTray) -> bool {
        match tray.item(Entry::Start) {
            MenuItem::Standard(item) => item.enabled,
            _ => panic!("Start is a standard item"),
        }
    }

    fn wait_until(what: &str, mut ok: impl FnMut() -> bool) {
        let end = Instant::now() + Duration::from_secs(5);
        while !ok() {
            assert!(Instant::now() < end, "timed out waiting for {what}");
            thread::sleep(Duration::from_millis(10));
        }
    }

    fn reply_ok() -> Result<RawOutput, CliError> {
        Ok(RawOutput {
            exit_code: 0,
            stdout: r#"{"ok":true,"service":"active"}"#.into(),
        })
    }

    fn start_and_release(reply: Result<RawOutput, CliError>) {
        let (tray, cli, release) = tray_with_blocked_cli();
        assert!(start_enabled(&tray), "Start is enabled before a start");
        tray.start_server();
        wait_until("the start call", || cli.calls.load(Ordering::SeqCst) == 1);
        assert!(
            !start_enabled(&tray),
            "Start is disabled while a start runs"
        );
        tray.start_server();
        thread::sleep(Duration::from_millis(100));
        assert_eq!(cli.calls.load(Ordering::SeqCst), 1, "no second start call");
        release.send(reply).unwrap();
        wait_until("Start to be enabled again", || start_enabled(&tray));
    }

    #[test]
    fn start_is_disabled_while_a_start_runs_and_enabled_again_after_success() {
        start_and_release(reply_ok());
    }

    #[test]
    fn start_is_enabled_again_after_a_failed_start() {
        start_and_release(Err(CliError::Timeout));
    }
}
