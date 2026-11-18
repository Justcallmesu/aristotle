use tauri_build::{AppManifest, Attributes};

include!("src/command_list.rs");

fn main() {
    // Only the app's own commands exist for the frontend (P4): each gets an `allow-<name>` permission.
    let manifest = AppManifest::new().commands(COMMANDS);
    tauri_build::try_build(Attributes::new().app_manifest(manifest)).expect("tauri build");
}
