//! Rust-side strings (P10): tray menu, tooltips, notifications. English and Indonesian.
//!
//! Texts come from `docs/desktop/design.md` section 5 (`m_*`, `tip_*`, `n_*`, `now_t`, and the tray
//! tooltips `st_*` / `sub_hermunk`). Keys marked "added in DS3" are code-only: they are not in design.md yet.

use crate::diff::Notice;
use crate::snapshot::Reason;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Lang {
    En,
    Id,
}

impl Lang {
    /// First non-empty of `LC_ALL`, `LC_MESSAGES`, `LANG` decides; starting with `id` -> Indonesian.
    pub fn from_values(
        lc_all: Option<&str>,
        lc_messages: Option<&str>,
        lang: Option<&str>,
    ) -> Lang {
        let chosen = [lc_all, lc_messages, lang]
            .into_iter()
            .flatten()
            .find(|v| !v.is_empty());
        match chosen {
            Some(v) if is_indonesian(v) => Lang::Id,
            _ => Lang::En,
        }
    }

    pub fn from_env() -> Lang {
        let get = |name: &str| std::env::var(name).ok();
        Lang::from_values(
            get("LC_ALL").as_deref(),
            get("LC_MESSAGES").as_deref(),
            get("LANG").as_deref(),
        )
    }
}

/// `id`, `id_ID`, `id_ID.UTF-8`, `id@x`; not `ida` or `iw`.
fn is_indonesian(locale: &str) -> bool {
    match locale.strip_prefix("id") {
        Some(rest) => rest.is_empty() || rest.starts_with(['_', '.', '@']),
        None => false,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Key {
    MenuOpen,
    MenuPair,
    MenuStart,
    MenuStop,
    MenuQuit,
    TipOk,
    TipWarn,
    TipErr,
    /// Added in DS3.
    TipNotInstalled,
    TipNoCli,
    TipNoAnswer,
    TipMismatch,
    /// `st_outside` (P17).
    TipOutside,
    /// `sub_hermunk` (P17).
    TipHermesUnknown,
    /// `now_t`.
    Now,
    PairedTitle,
    PairedBody,
    TermTitle,
    TermBody,
    HermesTitle,
    HermesBody,
    StopTitle,
    StopBody,
    /// Sent by the window app after a terminal change (D7); kept here with the other texts.
    TermOnTitle,
    TermOnBody,
    /// Added in DS3: the window app binary is missing.
    NoAppTitle,
    NoAppBody,
    /// Added in DS3: the name used when the devices list has no such device.
    ADevice,
    /// The notification action label.
    OpenAction,
}

impl Key {
    pub const ALL: [Key; 29] = [
        Key::MenuOpen,
        Key::MenuPair,
        Key::MenuStart,
        Key::MenuStop,
        Key::MenuQuit,
        Key::TipOk,
        Key::TipWarn,
        Key::TipErr,
        Key::TipNotInstalled,
        Key::TipNoCli,
        Key::TipNoAnswer,
        Key::TipMismatch,
        Key::TipOutside,
        Key::TipHermesUnknown,
        Key::Now,
        Key::PairedTitle,
        Key::PairedBody,
        Key::TermTitle,
        Key::TermBody,
        Key::HermesTitle,
        Key::HermesBody,
        Key::StopTitle,
        Key::StopBody,
        Key::TermOnTitle,
        Key::TermOnBody,
        Key::NoAppTitle,
        Key::NoAppBody,
        Key::ADevice,
        Key::OpenAction,
    ];
}

/// The text for `key`. Placeholders (`{name}`, `{via}`) are filled by the caller.
pub fn text(lang: Lang, key: Key) -> &'static str {
    match lang {
        Lang::En => en(key),
        Lang::Id => id(key),
    }
}

fn en(key: Key) -> &'static str {
    match key {
        Key::MenuOpen => "Open",
        Key::MenuPair => "Pair a phone…",
        Key::MenuStart => "Start server",
        Key::MenuStop => "Stop server…",
        Key::MenuQuit => "Quit",
        Key::TipOk => "Server running · Hermes reachable",
        Key::TipWarn => "Server running · Hermes not reachable",
        Key::TipErr => "Server stopped",
        Key::TipNotInstalled => "Service not installed",
        Key::TipNoCli => "aristotle-server not found",
        Key::TipNoAnswer => "Server not answering",
        Key::TipMismatch => "Server and app don’t match",
        Key::TipOutside => "Running outside the service",
        Key::TipHermesUnknown => {
            "This server does not report Hermes. Update it with the installer."
        }
        Key::Now => "now",
        Key::PairedTitle => "New phone paired",
        Key::PairedBody => "{name} can now reach this PC.",
        Key::TermTitle => "Remote terminal opened",
        Key::TermBody => "{name} opened a shell on this PC ({via}).",
        Key::HermesTitle => "Hermes is not reachable",
        Key::HermesBody => "Phones can’t start chats until Hermes runs again.",
        Key::StopTitle => "Aristotle server stopped",
        Key::StopBody => "It was not stopped from this app. Open the app to start it again.",
        Key::TermOnTitle => "Remote terminal turned on",
        Key::TermOnBody => "Granted phones can open a shell with full access to your account.",
        Key::NoAppTitle => "Window app not found",
        Key::NoAppBody => "aristotle-desktop is not installed next to the tray.",
        Key::ADevice => "A device",
        Key::OpenAction => "Open",
    }
}

fn id(key: Key) -> &'static str {
    match key {
        Key::MenuOpen => "Buka",
        Key::MenuPair => "Pasangkan ponsel…",
        Key::MenuStart => "Nyalakan server",
        Key::MenuStop => "Hentikan server…",
        Key::MenuQuit => "Keluar",
        Key::TipOk => "Server berjalan · Hermes terjangkau",
        Key::TipWarn => "Server berjalan · Hermes tidak terjangkau",
        Key::TipErr => "Server berhenti",
        Key::TipNotInstalled => "Layanan belum terpasang",
        Key::TipNoCli => "aristotle-server tidak ditemukan",
        Key::TipNoAnswer => "Server tidak menjawab",
        Key::TipMismatch => "Server dan aplikasi tidak cocok",
        Key::TipOutside => "Berjalan di luar layanan",
        Key::TipHermesUnknown => "Server ini tidak melaporkan Hermes. Perbarui dengan installer.",
        Key::Now => "baru saja",
        Key::PairedTitle => "Ponsel baru dipasangkan",
        Key::PairedBody => "{name} sekarang bisa terhubung ke PC ini.",
        Key::TermTitle => "Terminal jarak jauh dibuka",
        Key::TermBody => "{name} membuka shell di PC ini ({via}).",
        Key::HermesTitle => "Hermes tidak terjangkau",
        Key::HermesBody => "Ponsel belum bisa memulai obrolan sampai Hermes berjalan lagi.",
        Key::StopTitle => "Server Aristotle berhenti",
        Key::StopBody => {
            "Bukan dihentikan dari aplikasi ini. Buka aplikasi untuk menyalakannya lagi."
        }
        Key::TermOnTitle => "Terminal jarak jauh diaktifkan",
        Key::TermOnBody => {
            "Ponsel yang diberi izin bisa membuka shell dengan akses penuh ke akunmu."
        }
        Key::NoAppTitle => "Aplikasi jendela tidak ditemukan",
        Key::NoAppBody => "aristotle-desktop tidak terpasang di samping tray.",
        Key::ADevice => "Sebuah perangkat",
        Key::OpenAction => "Buka",
    }
}

/// The shown app name in notifications (not translated, as in the board).
pub const APP_NAME: &str = "Aristotle Server";

pub fn tooltip(lang: Lang, reason: Reason) -> &'static str {
    let key = match reason {
        Reason::AllOk => Key::TipOk,
        Reason::HermesDown => Key::TipWarn,
        Reason::HermesUnknown => Key::TipHermesUnknown,
        Reason::RunningOutsideService => Key::TipOutside,
        Reason::ServerStopped => Key::TipErr,
        Reason::NotInstalled => Key::TipNotInstalled,
        Reason::NoAnswer => Key::TipNoAnswer,
        Reason::CliMissing => Key::TipNoCli,
        Reason::VersionMismatch => Key::TipMismatch,
    };
    text(lang, key)
}

/// Title and body for a notice. Uses only device names and listener words.
pub fn notice_text(lang: Lang, notice: &Notice) -> (String, String) {
    match notice {
        Notice::DevicePaired { name } => (
            text(lang, Key::PairedTitle).to_owned(),
            text(lang, Key::PairedBody).replace("{name}", name),
        ),
        Notice::TerminalOpened {
            device_name,
            listener,
        } => {
            let name = device_name
                .as_deref()
                .unwrap_or_else(|| text(lang, Key::ADevice));
            let body = text(lang, Key::TermBody)
                .replace("{name}", name)
                .replace("{via}", &listener_label(listener));
            (text(lang, Key::TermTitle).to_owned(), body)
        }
        Notice::HermesUnreachable => (
            text(lang, Key::HermesTitle).to_owned(),
            text(lang, Key::HermesBody).to_owned(),
        ),
        Notice::ServerStopped => (
            text(lang, Key::StopTitle).to_owned(),
            text(lang, Key::StopBody).to_owned(),
        ),
    }
}

/// The board's example says `(LAN)`: the listener kind is shown, `lan` and `tailscale` prettified.
fn listener_label(listener: &str) -> String {
    match listener {
        "lan" => "LAN".to_owned(),
        "tailscale" => "Tailscale".to_owned(),
        other => other.to_owned(),
    }
}
