use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;
use tauri::image::Image;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_notification::NotificationExt;

static N: AtomicUsize = AtomicUsize::new(0);

fn open_window(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.set_focus();
        eprintln!("SPIKE open: window exists, focus");
        return;
    }
    let n = N.fetch_add(1, Ordering::SeqCst) + 1;
    let mut b = WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
        .title("spike")
        .inner_size(480.0, 320.0);
    // variant "ctx": give every window its own WebContext (data directory)
    if std::env::var("SPIKE_VARIANT").as_deref() == Ok("ctx") {
        b = b.data_directory(std::env::temp_dir().join(format!("tauri-tray-spike-{n}")));
    }
    match b.build() {
        Ok(w) => {
            eprintln!("SPIKE open: created window #{n}");
            // test hook: the same path as the titlebar close button (CloseRequested -> destroy)
            if let Some(s) = std::env::var("SPIKE_CLOSE_AFTER")
                .ok()
                .and_then(|v| v.parse::<u64>().ok())
            {
                std::thread::spawn(move || {
                    std::thread::sleep(Duration::from_secs(s));
                    let app = w.app_handle().clone();
                    if std::env::var("SPIKE_VARIANT").as_deref() == Ok("kill") {
                        use webkit2gtk::WebViewExt;
                        let r = w.with_webview(|wv| wv.inner().terminate_web_process());
                        eprintln!("SPIKE terminate_web_process: {r:?}");
                    }
                    let r = if std::env::var("SPIKE_VARIANT").as_deref() == Ok("destroy") {
                        w.destroy()
                    } else {
                        w.close()
                    };
                    drop(w);
                    std::thread::sleep(Duration::from_millis(500));
                    eprintln!(
                        "SPIKE close: {r:?}, windows left: {}",
                        app.webview_windows().len()
                    );
                });
            }
        }
        Err(e) => eprintln!("SPIKE open: failed {e}"),
    }
}

fn notify(app: &AppHandle) {
    let r = app
        .notification()
        .builder()
        .title("Spike")
        .body("hello from tray")
        .show();
    eprintln!("SPIKE notify: {r:?}");
}

fn window_only() -> bool {
    std::env::var("SPIKE_WINDOW_ONLY").as_deref() == Ok("1")
}

fn main() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            if window_only() {
                // DS2b child mode: no tray, window at start, process exits when it closes
                open_window(app.handle());
                return Ok(());
            }
            let open = MenuItem::with_id(app, "open", "Open", true, None::<&str>)?;
            let note = MenuItem::with_id(app, "notify", "Notify", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &note, &quit])?;
            let icons = [
                Image::from_bytes(include_bytes!("../icons/green.png"))?,
                Image::from_bytes(include_bytes!("../icons/amber.png"))?,
                Image::from_bytes(include_bytes!("../icons/red.png"))?,
            ];
            let tray = TrayIconBuilder::with_id("spike")
                .icon(icons[0].clone())
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, ev| {
                    eprintln!("SPIKE menu: {}", ev.id.as_ref());
                    match ev.id.as_ref() {
                        "open" => open_window(app),
                        "notify" => notify(app),
                        "quit" => app.exit(0),
                        _ => {}
                    }
                })
                .on_tray_icon_event(|tray, ev| {
                    eprintln!("SPIKE tray event: {ev:?}");
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = ev
                    {
                        open_window(tray.app_handle());
                    }
                })
                .build(app)?;
            std::thread::spawn(move || {
                let mut i = 0usize;
                loop {
                    std::thread::sleep(Duration::from_secs(2));
                    i = (i + 1) % 3;
                    let _ = tray.set_icon(Some(icons[i].clone()));
                    eprintln!("SPIKE icon {i}");
                }
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("build");
    app.run(|_app, ev| {
        if let RunEvent::ExitRequested { code, api, .. } = ev {
            // window close must not quit the app; only explicit app.exit (code Some)
            if code.is_none() && !window_only() {
                api.prevent_exit();
            }
        }
    });
}
