//! DS2b spike: resident ksni tray that launches a child window app on left click.
use ksni::blocking::{Handle, TrayMethods};
use ksni::menu::StandardItem;
use ksni::{Icon, MenuItem, OfflineReason, Tray};
use std::process::{Child, Command};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

const SIZE: i32 = 22;
const COLOURS: [[u8; 3]; 3] = [[0x2e, 0xcc, 0x40], [0xff, 0xb0, 0x00], [0xe0, 0x30, 0x30]];
const DEFAULT_CHILD: &str = concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../tauri-tray/src-tauri/target/release/tauri-tray"
);

/// Filled circle, ARGB32 network byte order, generated in memory.
fn pixmap(rgb: [u8; 3]) -> Icon {
    let c = (SIZE as f32 - 1.0) / 2.0;
    let mut data = Vec::with_capacity((SIZE * SIZE * 4) as usize);
    for y in 0..SIZE {
        for x in 0..SIZE {
            let d = ((x as f32 - c).powi(2) + (y as f32 - c).powi(2)).sqrt();
            let a = if d <= c - 1.0 { 0xff } else { 0x00 };
            data.extend_from_slice(&[a, rgb[0], rgb[1], rgb[2]]);
        }
    }
    Icon {
        width: SIZE,
        height: SIZE,
        data,
    }
}

fn log(msg: &str) {
    let t = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0);
    eprintln!("KSNI {t:.3} {msg}");
}

fn child_path() -> String {
    std::env::var("ARISTOTLE_WINDOW_BIN").unwrap_or_else(|_| DEFAULT_CHILD.to_string())
}

/// Child PID held by the tray; None = no child running.
type ChildPid = Arc<Mutex<Option<u32>>>;

struct SpikeTray {
    colour: usize,
    child: ChildPid,
}

/// Launch the window app unless one runs. A reaper thread waits on it (no zombie).
fn open_window(child: &ChildPid) {
    let mut guard = child.lock().unwrap();
    if let Some(pid) = *guard {
        log(&format!(
            "open: child {pid} already running, not starting another"
        ));
        return;
    }
    let mut cmd = Command::new(child_path());
    cmd.env("SPIKE_WINDOW_ONLY", "1");
    // Wayland focus: the host hands an XDG activation token with the click.
    for _ in 0..5 {
        if let Some(tok) = ksni::current_xdg_activation_token() {
            log("open: got xdg activation token");
            cmd.env("XDG_ACTIVATION_TOKEN", tok);
            break;
        }
        thread::sleep(Duration::from_millis(2));
    }
    match cmd.spawn() {
        Ok(c) => {
            *guard = Some(c.id());
            log(&format!("open: spawned child pid {}", c.id()));
            let slot = Arc::clone(child);
            thread::spawn(move || reap(c, slot));
        }
        Err(e) => log(&format!("open: spawn failed: {e}")),
    }
}

fn reap(mut c: Child, slot: ChildPid) {
    let pid = c.id();
    let status = c.wait();
    *slot.lock().unwrap() = None;
    log(&format!("child {pid} exited: {status:?} (reaped)"));
}

fn notify() {
    let r = notify_rust::Notification::new()
        .appname("ksni-tray")
        .summary("DS2b spike")
        .body("hello from the tray process")
        .action("open", "Open")
        .show();
    match r {
        Ok(h) => {
            log("notify: shown");
            // blocks until the toast is closed or an action is clicked
            thread::spawn(move || {
                h.wait_for_action(|a| log(&format!("notify: action/close = {a}")));
            });
        }
        Err(e) => log(&format!("notify: failed {e}")),
    }
}

impl Tray for SpikeTray {
    fn id(&self) -> String {
        "ksni-tray-spike".into()
    }
    fn title(&self) -> String {
        "DS2b ksni spike".into()
    }
    fn icon_pixmap(&self) -> Vec<Icon> {
        vec![pixmap(COLOURS[self.colour])]
    }
    fn activate(&mut self, x: i32, y: i32) {
        log(&format!("ACTIVATE (left click) at {x},{y}"));
        open_window(&self.child);
    }
    fn menu(&self) -> Vec<MenuItem<Self>> {
        vec![
            StandardItem {
                label: "Open".into(),
                activate: Box::new(|t: &mut Self| {
                    log("menu: Open");
                    open_window(&t.child);
                }),
                ..Default::default()
            }
            .into(),
            StandardItem {
                label: "Notify".into(),
                activate: Box::new(|_| {
                    log("menu: Notify");
                    notify();
                }),
                ..Default::default()
            }
            .into(),
            StandardItem {
                label: "Quit".into(),
                activate: Box::new(|_| {
                    log("menu: Quit");
                    std::process::exit(0);
                }),
                ..Default::default()
            }
            .into(),
        ]
    }
    fn watcher_online(&self) {
        log("watcher online");
    }
    fn watcher_offline(&self, reason: OfflineReason) -> bool {
        log(&format!("watcher offline: {reason:?}"));
        true
    }
}

fn cycle(handle: &Handle<SpikeTray>) {
    let mut i = 0usize;
    loop {
        thread::sleep(Duration::from_secs(2));
        i = (i + 1) % COLOURS.len();
        if handle.update(|t| t.colour = i).is_none() {
            log("tray service gone, exiting");
            return;
        }
        log(&format!("icon {i}"));
    }
}

fn main() {
    let tray = SpikeTray {
        colour: 0,
        child: Arc::new(Mutex::new(None)),
    };
    log(&format!(
        "start pid {} child bin {}",
        std::process::id(),
        child_path()
    ));
    match tray.spawn() {
        Ok(handle) => cycle(&handle),
        Err(e) => log(&format!("spawn failed: {e}")),
    }
}
