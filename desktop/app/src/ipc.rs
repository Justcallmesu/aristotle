//! The Tauri commands and events: thin wrappers that hand blocking work to a worker thread and call
//! the concern modules. No logic lives here.

use crate::args::StartIntent;
use crate::backend::Backend;
use crate::dto::{IpcError, IpcResult, SnapshotDto};
use crate::logs::{LogEvent, LogStream};
use crate::service::{run_service_action, ServiceAction};
use crate::{devices, pairing, terminal};
use aristotle_core::types::{ActionResult, Device, Pair, TerminalStatus};
use serde::Serialize;
use std::sync::{Arc, Mutex};
use std::time::SystemTime;
use tauri::{AppHandle, Emitter, State};

pub const EVENT_SNAPSHOT: &str = "snapshot";
pub const EVENT_START_INTENT: &str = "start-intent";
pub const EVENT_LOGS_LINES: &str = "logs-lines";
pub const EVENT_LOGS_ENDED: &str = "logs-ended";

type Shared<'a> = State<'a, Arc<Backend>>;

/// The intent the window was started with, until the frontend has read it (it cannot listen yet when
/// the process starts; hand-offs reach it as the `start-intent` event).
#[derive(Default)]
pub struct PendingIntent(pub Mutex<Option<StartIntent>>);

#[derive(Serialize, Clone)]
struct LogLines {
    lines: Vec<String>,
}

async fn blocking<T, F>(work: F) -> IpcResult<T>
where
    T: Send + 'static,
    F: FnOnce() -> IpcResult<T> + Send + 'static,
{
    tauri::async_runtime::spawn_blocking(work)
        .await
        .map_err(|_| IpcError::internal())?
}

/// Polls and tells the frontend, so a state change shows without waiting for the next 5 s tick.
pub fn publish(app: &AppHandle, backend: &Backend) -> SnapshotDto {
    let dto = backend.refresh();
    let _ = app.emit(EVENT_SNAPSHOT, &dto);
    dto
}

#[tauri::command]
pub async fn snapshot(backend: Shared<'_>, fresh: Option<bool>) -> IpcResult<SnapshotDto> {
    let backend = backend.inner().clone();
    blocking(move || {
        Ok(if fresh.unwrap_or(false) {
            backend.refresh()
        } else {
            backend.current()
        })
    })
    .await
}

#[tauri::command]
pub fn start_intent(pending: State<'_, PendingIntent>) -> StartIntent {
    let mut slot = pending.0.lock().unwrap_or_else(|e| e.into_inner());
    slot.take().unwrap_or(StartIntent::Status)
}

#[tauri::command]
pub async fn service_action(
    app: AppHandle,
    backend: Shared<'_>,
    action: ServiceAction,
) -> IpcResult<ActionResult> {
    let backend = backend.inner().clone();
    blocking(move || {
        let done = run_service_action(&backend, action, SystemTime::now());
        publish(&app, &backend);
        done
    })
    .await
}

#[tauri::command]
pub async fn pair(backend: Shared<'_>, name: Option<String>) -> IpcResult<Pair> {
    let backend = backend.inner().clone();
    blocking(move || pairing::issue_code(&backend, name.as_deref())).await
}

#[tauri::command]
pub async fn devices_revoke(app: AppHandle, backend: Shared<'_>, id: String) -> IpcResult<Device> {
    let backend = backend.inner().clone();
    blocking(move || {
        let revoked = devices::revoke(&backend, &id);
        publish(&app, &backend);
        revoked
    })
    .await
}

#[tauri::command]
pub async fn terminal_status(backend: Shared<'_>) -> IpcResult<TerminalStatus> {
    let backend = backend.inner().clone();
    blocking(move || terminal::status(&backend)).await
}

/// Runs a terminal change, then publishes a fresh snapshot (the Terminal and Devices counts follow it).
async fn terminal_change(
    app: AppHandle,
    backend: Shared<'_>,
    change: impl FnOnce(&Backend) -> IpcResult<TerminalStatus> + Send + 'static,
) -> IpcResult<TerminalStatus> {
    let backend = backend.inner().clone();
    blocking(move || {
        let status = change(&backend);
        publish(&app, &backend);
        status
    })
    .await
}

#[tauri::command]
pub async fn terminal_grant(
    app: AppHandle,
    backend: Shared<'_>,
    id: String,
) -> IpcResult<TerminalStatus> {
    terminal_change(app, backend, move |b| terminal::grant(b, &id)).await
}

#[tauri::command]
pub async fn terminal_ungrant(
    app: AppHandle,
    backend: Shared<'_>,
    id: String,
) -> IpcResult<TerminalStatus> {
    terminal_change(app, backend, move |b| terminal::ungrant(b, &id)).await
}

#[tauri::command]
pub async fn terminal_enable(app: AppHandle, backend: Shared<'_>) -> IpcResult<TerminalStatus> {
    terminal_change(app, backend, terminal::enable).await
}

#[tauri::command]
pub async fn terminal_disable(app: AppHandle, backend: Shared<'_>) -> IpcResult<TerminalStatus> {
    terminal_change(app, backend, terminal::disable).await
}

#[tauri::command]
pub async fn logs_start(
    app: AppHandle,
    backend: Shared<'_>,
    logs: State<'_, Arc<LogStream>>,
) -> IpcResult<()> {
    let (backend, logs) = (backend.inner().clone(), logs.inner().clone());
    blocking(move || {
        logs.start(backend.bin.as_deref(), move |event| match event {
            LogEvent::Lines(lines) => {
                let _ = app.emit(EVENT_LOGS_LINES, LogLines { lines });
            }
            LogEvent::Ended => {
                let _ = app.emit(EVENT_LOGS_ENDED, ());
            }
        })
    })
    .await
}

#[tauri::command]
pub async fn logs_stop(logs: State<'_, Arc<LogStream>>) -> IpcResult<()> {
    let logs = logs.inner().clone();
    blocking(move || {
        logs.stop();
        Ok(())
    })
    .await
}
