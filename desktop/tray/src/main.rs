//! `aristotle-tray`: resident StatusNotifierItem tray (D17, D18). No GUI toolkit, no webview.

mod icons;
mod launcher;
mod menu;
mod notifier;
mod starting;
mod tray;

use aristotle_core::poller::Poller;
use aristotle_core::runner::{MissingCli, RealCli, ServerCli};
use aristotle_core::snapshot::{desktop_version, Snapshot};
use aristotle_core::stamp::ExpectedStop;
use aristotle_core::state::Assessment;
use aristotle_core::strings::Lang;
use aristotle_core::POLL_INTERVAL;
use ksni::blocking::{Handle, TrayMethods};
use std::sync::Arc;
use std::thread;
use std::time::SystemTime;
use tray::AristotleTray;

fn main() {
    let lang = Lang::from_env();
    let cli: Arc<dyn ServerCli> = match RealCli::from_env() {
        Some(real) => Arc::new(real),
        None => Arc::new(MissingCli),
    };
    let launcher = launcher::Launcher::from_env();
    let mut poller = Poller::new(desktop_version(), ExpectedStop::from_env());

    // First poll before the icon exists, so it never flashes a wrong colour.
    let first = poller.tick(cli.as_ref(), SystemTime::now());
    let tray = AristotleTray::new(
        &first.snapshot,
        lang,
        Arc::clone(&launcher),
        Arc::clone(&cli),
    );
    let handle = match tray.assume_sni_available(true).spawn() {
        Ok(handle) => handle,
        Err(e) => {
            eprintln!("aristotle-tray: could not start the tray: {e}");
            std::process::exit(1);
        }
    };
    poll_forever(poller, cli, handle, lang, launcher);
}

fn poll_forever(
    mut poller: Poller,
    cli: Arc<dyn ServerCli>,
    handle: Handle<AristotleTray>,
    lang: Lang,
    launcher: Arc<launcher::Launcher>,
) {
    loop {
        thread::sleep(POLL_INTERVAL);
        let tick = poller.tick(cli.as_ref(), SystemTime::now());
        if !update_tray(&handle, &tick.snapshot) {
            eprintln!("aristotle-tray: tray service gone, exiting");
            return;
        }
        for notice in &tick.notices {
            notifier::show_notice(lang, notice, &launcher);
        }
    }
}

/// Pushes the snapshot to the tray (ksni compares the properties itself); `apply`'s flag only gates the log line.
/// Returns false when the tray service is gone.
fn update_tray(handle: &Handle<AristotleTray>, snapshot: &Snapshot) -> bool {
    let changed = handle.update(|t| (t.apply(snapshot), t.assessment));
    match changed {
        Some((true, Assessment { state, reason })) => {
            eprintln!("aristotle-tray: state {state:?} ({reason:?})");
            true
        }
        Some(_) => true,
        None => false,
    }
}
