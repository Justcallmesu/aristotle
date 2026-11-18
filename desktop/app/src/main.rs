//! `aristotle-desktop`: one window; the process ends when the window closes (D18).

use aristotle_core::POLL_INTERVAL;
use aristotle_desktop::args::parse_args;
use aristotle_desktop::backend::Backend;
use aristotle_desktop::ipc::{self, PendingIntent};
use aristotle_desktop::logs::LogStream;
use std::sync::Arc;
use tauri::{AppHandle, Emitter, Manager, RunEvent, WindowEvent};

const WINDOW: &str = "main";

fn main() {
    let intent = parse_args(&std::env::args().collect::<Vec<_>>());
    let backend = Arc::new(Backend::from_env());
    let logs = Arc::new(LogStream::new());

    let app = tauri::Builder::default()
        // A second launch hands its arguments to this process and exits (P14).
        .plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| {
            focus_window(app);
            let _ = app.emit(ipc::EVENT_START_INTENT, parse_args(&argv));
        }))
        .manage(backend.clone())
        .manage(logs.clone())
        .manage(PendingIntent(std::sync::Mutex::new(Some(intent))))
        .invoke_handler(tauri::generate_handler![
            ipc::snapshot,
            ipc::start_intent,
            ipc::service_action,
            ipc::pair,
            ipc::devices_revoke,
            ipc::terminal_status,
            ipc::terminal_grant,
            ipc::terminal_ungrant,
            ipc::terminal_enable,
            ipc::terminal_disable,
            ipc::logs_start,
            ipc::logs_stop,
        ])
        .on_window_event({
            let logs = logs.clone();
            move |_window, event| {
                if matches!(event, WindowEvent::Destroyed) {
                    logs.stop();
                }
            }
        })
        .setup(move |app| {
            spawn_poller(app.handle().clone(), backend);
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("could not build the Aristotle Server window");

    app.run(move |_app, event| {
        if matches!(event, RunEvent::Exit) {
            logs.stop();
        }
    });
}

fn focus_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(WINDOW) {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

/// Polls every 5 s (P1) for as long as the process lives, which is as long as the window.
fn spawn_poller(app: AppHandle, backend: Arc<Backend>) {
    std::thread::spawn(move || loop {
        std::thread::sleep(POLL_INTERVAL);
        ipc::publish(&app, &backend);
    });
}
