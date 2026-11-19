//! `aristotle-tray`: resident StatusNotifierItem tray (D17, D18). No GUI toolkit, no webview.

mod icons;
mod instance;
mod launcher;
mod menu;
mod notifier;
mod offline;
mod starting;
#[cfg(test)]
mod testing;
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
use std::time::{Instant, SystemTime};
use tray::AristotleTray;

/// The `--version` line: `aristotle-tray <v>`, `0.0.0-dev` when the build carries no version.
fn version_line(version: Option<&str>) -> String {
    format!("aristotle-tray {}", version.unwrap_or("0.0.0-dev"))
}

/// Takes the tray lock. `None` when another tray runs (this one must exit); `Some` otherwise, also when there is no
/// runtime directory or the lock cannot be created: a tray without a lock is better than no tray.
fn single_instance() -> Option<Option<instance::Lock>> {
    let Some(path) = instance::lock_path_from_env() else {
        return Some(None);
    };
    match instance::acquire(&path) {
        Ok(instance::Instance::First(lock)) => Some(Some(lock)),
        Ok(instance::Instance::Second) => None,
        Err(e) => {
            eprintln!("aristotle-tray: no single-instance lock ({e}); starting anyway");
            Some(None)
        }
    }
}

fn main() {
    if std::env::args().nth(1).as_deref() == Some("--version") {
        println!("{}", version_line(desktop_version()));
        return;
    }
    // Single instance (P26): a second tray exits 0 before it registers anything. The lock is held until the process ends.
    let _instance = match single_instance() {
        Some(held) => held,
        None => return,
    };
    let lang = Lang::from_env();
    let cli: Arc<dyn ServerCli> = match RealCli::from_env() {
        Some(real) => Arc::new(real),
        None => Arc::new(MissingCli),
    };
    let launcher = launcher::Launcher::from_env(move || notifier::show_window_failed(lang));
    let mut poller = Poller::new(desktop_version(), ExpectedStop::from_env());

    // First poll before the icon exists, so it never flashes a wrong colour.
    let first = poller.tick(cli.as_ref(), SystemTime::now());
    let watch = offline::SharedOfflineWatch::default();
    let tray = AristotleTray::new(
        &first.snapshot,
        lang,
        Arc::clone(&launcher),
        Arc::clone(&cli),
        Arc::clone(&watch),
    );
    let handle = match tray.assume_sni_available(true).spawn() {
        Ok(handle) => handle,
        Err(e) => {
            eprintln!("aristotle-tray: could not start the tray: {e}");
            std::process::exit(1);
        }
    };
    poll_forever(poller, cli, handle, lang, launcher, &watch);
}

fn poll_forever(
    mut poller: Poller,
    cli: Arc<dyn ServerCli>,
    handle: Handle<AristotleTray>,
    lang: Lang,
    launcher: Arc<launcher::Launcher>,
    watch: &offline::SharedOfflineWatch,
) {
    loop {
        thread::sleep(POLL_INTERVAL);
        // P27 (1): the panel's watcher has been gone for good; end this tray so the lock is free for the next login's.
        if offline::lock(watch).expired(Instant::now(), offline::OFFLINE_LIMIT) {
            eprintln!("aristotle-tray: watcher offline for 30 s, exiting");
            return;
        }
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

#[cfg(test)]
mod tests {
    use super::version_line;

    #[test]
    fn version_line_uses_the_build_version() {
        assert_eq!(version_line(Some("1.2.3")), "aristotle-tray 1.2.3");
    }

    #[test]
    fn version_line_falls_back_to_dev() {
        assert_eq!(version_line(None), "aristotle-tray 0.0.0-dev");
    }
}
