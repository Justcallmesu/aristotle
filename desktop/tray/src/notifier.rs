//! Desktop notifications through notify-rust. A notice's default action opens the window app.

use crate::launcher::Launcher;
use aristotle_core::diff::Notice;
use aristotle_core::strings::{notice_text, text, Key, Lang, APP_NAME};
use std::sync::Arc;
use std::thread;

const DEFAULT_ACTION: &str = "default";
const ICON: &str = "network-server";

pub fn show_notice(lang: Lang, notice: &Notice, launcher: &Arc<Launcher>) {
    let (title, body) = notice_text(lang, notice);
    show(
        lang,
        &title,
        &body,
        notice.has_open_action().then_some(launcher),
    );
}

pub fn show_window_missing(lang: Lang) {
    show(
        lang,
        text(lang, Key::NoAppTitle),
        text(lang, Key::NoAppBody),
        None,
    );
}

/// The window app died right after launch (P11: no child output in the text).
pub fn show_window_failed(lang: Lang) {
    show(
        lang,
        text(lang, Key::WindowFailedTitle),
        text(lang, Key::WindowFailedBody),
        None,
    );
}

fn show(lang: Lang, title: &str, body: &str, open_on_click: Option<&Arc<Launcher>>) {
    let mut notification = notify_rust::Notification::new();
    notification
        .appname(APP_NAME)
        .icon(ICON)
        .summary(title)
        .body(body);
    // The "Open" action only when a handler listens for it.
    if open_on_click.is_some() {
        notification.action(DEFAULT_ACTION, text(lang, Key::OpenAction));
    }
    let result = notification.show();
    let Ok(handle) = result else {
        eprintln!("aristotle-tray: notification could not be shown");
        return;
    };
    if let Some(launcher) = open_on_click.map(Arc::clone) {
        // Blocks until the toast closes or is clicked, so it gets its own thread.
        thread::spawn(move || {
            handle.wait_for_action(|action| {
                if action == DEFAULT_ACTION {
                    launcher.open(&[], None);
                }
            });
        });
    }
}
