//! P4: the frontend gets only the app's own commands. Parses the capability file, the Tauri config and
//! the manifest instead of trusting them.

use aristotle_desktop::COMMANDS;
use serde_json::Value;
use std::collections::BTreeSet;
use std::fs;
use std::path::PathBuf;

fn app_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn json(path: &str) -> Value {
    let text = fs::read_to_string(app_dir().join(path)).expect("file readable");
    serde_json::from_str(&text).expect("valid json")
}

fn permissions() -> Vec<String> {
    json("capabilities/default.json")["permissions"]
        .as_array()
        .expect("permissions array")
        .iter()
        .map(|p| p.as_str().expect("permission is a string").to_owned())
        .collect()
}

#[test]
fn the_capability_file_allows_exactly_the_listed_commands_and_event_listening() {
    let mut expected: BTreeSet<String> = COMMANDS
        .iter()
        .map(|c| format!("allow-{}", c.replace('_', "-")))
        .collect();
    expected.insert("core:event:allow-listen".into());
    expected.insert("core:event:allow-unlisten".into());
    let actual: BTreeSet<String> = permissions().into_iter().collect();
    assert_eq!(actual, expected);
}

#[test]
fn no_plugin_or_broad_core_permission_is_granted() {
    for permission in permissions() {
        assert!(!permission.ends_with(":default"), "{permission}");
        for forbidden in [
            "shell:",
            "fs:",
            "http:",
            "opener:",
            "core:webview",
            "core:window",
        ] {
            assert!(!permission.starts_with(forbidden), "{permission}");
        }
    }
}

#[test]
fn only_the_main_window_has_a_capability_and_there_is_one_file() {
    let capability = json("capabilities/default.json");
    assert_eq!(capability["windows"], serde_json::json!(["main"]));
    let files: Vec<_> = fs::read_dir(app_dir().join("capabilities"))
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    assert_eq!(files, ["default.json"]);
}

#[test]
fn the_csp_is_strict_and_the_global_tauri_object_is_off() {
    let config = json("tauri.conf.json");
    assert_eq!(config["app"]["withGlobalTauri"], Value::Bool(false));
    let csp = config["app"]["security"]["csp"].as_str().expect("csp set");
    assert!(!csp.contains("unsafe-eval"), "{csp}");
    assert!(!csp.contains("unsafe-inline"), "{csp}");
    assert!(csp.contains("default-src 'none'"), "{csp}");
    // The only network-looking source is Tauri's own IPC.
    for source in csp
        .split([' ', ';'])
        .filter(|s| s.contains("://") || s.contains('*'))
    {
        assert!(
            source == "http://ipc.localhost",
            "remote source in the CSP: {source}"
        );
    }
}

#[test]
fn the_app_depends_on_no_shell_fs_http_or_opener_plugin() {
    let manifest = fs::read_to_string(app_dir().join("Cargo.toml")).unwrap();
    for plugin in [
        "tauri-plugin-shell",
        "tauri-plugin-fs",
        "tauri-plugin-http",
        "tauri-plugin-opener",
        "tauri-plugin-notification",
    ] {
        assert!(!manifest.contains(plugin), "{plugin} in Cargo.toml");
    }
}

#[test]
fn the_tray_does_not_depend_on_tauri_or_the_app() {
    let manifest = fs::read_to_string(app_dir().join("../tray/Cargo.toml")).unwrap();
    assert!(!manifest.contains("tauri"));
    assert!(!manifest.contains("aristotle-desktop"));
}
