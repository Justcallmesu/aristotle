//! The ksni tray object: icon, tooltip, menu, click handling.

use crate::icons;
use crate::launcher::{Launch, Launcher};
use crate::menu::{self, Entry, MenuInput};
use crate::notifier;
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

pub struct AristotleTray {
    pub assessment: Assessment,
    pub menu_input: MenuInput,
    pub lang: Lang,
    pub launcher: Arc<Launcher>,
    pub cli: Arc<dyn ServerCli>,
    starting: Starting,
}

impl AristotleTray {
    pub fn new(
        snapshot: &Snapshot,
        lang: Lang,
        launcher: Arc<Launcher>,
        cli: Arc<dyn ServerCli>,
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
        eprintln!("aristotle-tray: watcher online");
    }

    fn watcher_offline(&self, reason: OfflineReason) -> bool {
        eprintln!("aristotle-tray: watcher offline ({reason:?}), waiting");
        true
    }
}
