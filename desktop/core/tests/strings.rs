//! Locale selection and string table parity (P10).

use aristotle_core::diff::Notice;
use aristotle_core::snapshot::Reason;
use aristotle_core::strings::*;
use std::collections::BTreeSet;

#[test]
fn locale_order_is_lc_all_then_lc_messages_then_lang() {
    use Lang::*;
    assert_eq!(
        Lang::from_values(Some("id_ID.UTF-8"), Some("en_US"), Some("en_US")),
        Id
    );
    assert_eq!(
        Lang::from_values(Some("en_US"), Some("id_ID"), Some("id_ID")),
        En
    );
    assert_eq!(
        Lang::from_values(None, Some("id_ID.UTF-8"), Some("en_US")),
        Id
    );
    assert_eq!(Lang::from_values(None, Some("en_US"), Some("id_ID")), En);
    assert_eq!(Lang::from_values(None, None, Some("id_ID.UTF-8")), Id);
    assert_eq!(Lang::from_values(Some(""), Some(""), Some("id")), Id);
    assert_eq!(Lang::from_values(None, None, Some("id@x")), Id);
}

#[test]
fn locale_defaults_to_english() {
    use Lang::*;
    assert_eq!(Lang::from_values(None, None, None), En);
    assert_eq!(Lang::from_values(Some(""), None, Some("")), En);
    assert_eq!(Lang::from_values(None, None, Some("C")), En);
    assert_eq!(Lang::from_values(None, None, Some("de_DE.UTF-8")), En);
    assert_eq!(Lang::from_values(None, None, Some("ida")), En);
}

fn placeholders(s: &str) -> BTreeSet<String> {
    s.split('{')
        .skip(1)
        .filter_map(|p| p.split('}').next())
        .map(str::to_owned)
        .collect()
}

#[test]
fn every_key_has_text_in_both_tables_with_the_same_placeholders() {
    let distinct: BTreeSet<String> = Key::ALL.iter().map(|k| format!("{k:?}")).collect();
    assert_eq!(distinct.len(), Key::ALL.len(), "duplicate in Key::ALL");
    for key in Key::ALL {
        let (en, id) = (text(Lang::En, key), text(Lang::Id, key));
        assert!(
            !en.trim().is_empty() && !id.trim().is_empty(),
            "{key:?} empty"
        );
        assert_eq!(
            placeholders(en),
            placeholders(id),
            "{key:?} placeholders differ"
        );
    }
}

#[test]
fn texts_differ_between_languages_except_the_brand_like_ones() {
    let same: Vec<_> = Key::ALL
        .iter()
        .filter(|k| text(Lang::En, **k) == text(Lang::Id, **k))
        .collect();
    assert!(same.is_empty(), "identical EN and ID text: {same:?}");
}

#[test]
fn board_texts_are_exact() {
    assert_eq!(text(Lang::En, Key::MenuPair), "Pair a phone…");
    assert_eq!(text(Lang::Id, Key::MenuStart), "Nyalakan server");
    assert_eq!(
        text(Lang::En, Key::TipOk),
        "Server running · Hermes reachable"
    );
    assert_eq!(text(Lang::Id, Key::StopTitle), "Server Aristotle berhenti");
}

#[test]
fn design_md_tray_texts_are_exact() {
    // docs/desktop/design.md section 5 (design wins over the first DS3 wording).
    let rows = [
        (Key::MenuStop, "Stop server…", "Hentikan server…"),
        (
            Key::TipOutside,
            "Running outside the service",
            "Berjalan di luar layanan",
        ),
        (
            Key::TipHermesUnknown,
            "This server does not report Hermes. Update it with the installer.",
            "Server ini tidak melaporkan Hermes. Perbarui dengan installer.",
        ),
        (Key::Now, "now", "baru saja"),
        (
            Key::TipNotInstalled,
            "Service not installed",
            "Layanan belum terpasang",
        ),
        (
            Key::TipMismatch,
            "Server and app don’t match",
            "Server dan aplikasi tidak cocok",
        ),
        (
            Key::TipNoCli,
            "aristotle-server not found",
            "aristotle-server tidak ditemukan",
        ),
        (
            Key::TipNoAnswer,
            "Server not answering",
            "Server tidak menjawab",
        ),
    ];
    for (key, en, id) in rows {
        assert_eq!(text(Lang::En, key), en, "{key:?} EN");
        assert_eq!(text(Lang::Id, key), id, "{key:?} ID");
    }
}

#[test]
fn the_new_reasons_pick_their_own_tooltips() {
    assert_eq!(
        tooltip(Lang::En, Reason::RunningOutsideService),
        "Running outside the service"
    );
    assert_eq!(
        tooltip(Lang::Id, Reason::HermesUnknown),
        "Server ini tidak melaporkan Hermes. Perbarui dengan installer."
    );
}

#[test]
fn notice_texts_fill_names_and_fall_back() {
    let (t, b) = notice_text(
        Lang::En,
        &Notice::DevicePaired {
            name: "pixel".into(),
        },
    );
    assert_eq!(
        (t.as_str(), b.as_str()),
        ("New phone paired", "pixel can now reach this PC.")
    );
    let (_, b) = notice_text(
        Lang::En,
        &Notice::TerminalOpened {
            device_name: Some("pixel".into()),
            listener: "lan".into(),
        },
    );
    assert_eq!(b, "pixel opened a shell on this PC (LAN).");
    let (_, b) = notice_text(
        Lang::Id,
        &Notice::TerminalOpened {
            device_name: None,
            listener: "tailscale".into(),
        },
    );
    assert_eq!(b, "Sebuah perangkat membuka shell di PC ini (Tailscale).");
    let (t, _) = notice_text(Lang::Id, &Notice::HermesUnreachable);
    assert_eq!(t, "Hermes tidak terjangkau");
    let (t, _) = notice_text(Lang::En, &Notice::ServerStopped);
    assert_eq!(t, "Aristotle server stopped");
}

#[test]
fn no_text_names_a_pairing_code_and_no_placeholder_is_left_in_notices() {
    for key in Key::ALL {
        for lang in [Lang::En, Lang::Id] {
            let s = text(lang, key).to_lowercase();
            assert!(!s.contains("code") && !s.contains("kode"), "{key:?}");
        }
    }
    let n = [
        Notice::DevicePaired { name: "x".into() },
        Notice::TerminalOpened {
            device_name: None,
            listener: "lan".into(),
        },
        Notice::HermesUnreachable,
        Notice::ServerStopped,
    ];
    for lang in [Lang::En, Lang::Id] {
        for notice in &n {
            let (t, b) = notice_text(lang, notice);
            assert!(!t.contains('{') && !b.contains('{'), "{notice:?}");
        }
    }
}

#[test]
fn every_reason_has_a_tooltip() {
    use Reason::*;
    for lang in [Lang::En, Lang::Id] {
        for r in [
            AllOk,
            HermesDown,
            HermesUnknown,
            RunningOutsideService,
            ServerStopped,
            NotInstalled,
            NoAnswer,
            CliMissing,
            VersionMismatch,
        ] {
            assert!(!tooltip(lang, r).is_empty());
        }
    }
    assert_eq!(
        tooltip(Lang::En, AllOk),
        "Server running · Hermes reachable"
    );
}

#[test]
fn the_d7_notice_uses_the_design_text_and_no_name() {
    {
        let notice = Notice::TerminalTurnedOn;
        let (t, b) = notice_text(Lang::En, &notice);
        assert_eq!(t, "Remote terminal turned on");
        assert_eq!(
            b,
            "Granted phones can open a shell with full access to your account."
        );
        let (t, b) = notice_text(Lang::Id, &notice);
        assert_eq!(t, "Terminal jarak jauh diaktifkan");
        assert_eq!(
            b,
            "Ponsel yang diberi izin bisa membuka shell dengan akses penuh ke akunmu."
        );
    }
}

#[test]
fn the_grant_notice_uses_the_design_text_with_the_device_name() {
    let named = Notice::TerminalGranted {
        device_name: Some("pixel".into()),
    };
    let (t, b) = notice_text(Lang::En, &named);
    assert_eq!(t, "Terminal access granted");
    assert_eq!(
        b,
        "pixel can open a shell with full access to your account while the remote terminal is on."
    );
    let (t, b) = notice_text(Lang::Id, &named);
    assert_eq!(t, "Akses terminal diberikan");
    assert_eq!(
        b,
        "pixel bisa membuka shell dengan akses penuh ke akunmu selama terminal jarak jauh aktif."
    );
    let anon = Notice::TerminalGranted { device_name: None };
    assert!(notice_text(Lang::En, &anon)
        .1
        .starts_with("A device can open"));
    assert!(notice_text(Lang::Id, &anon)
        .1
        .starts_with("Sebuah perangkat bisa"));
}
