/// Every IPC command the frontend may call. `build.rs` turns each into an `allow-<name>` permission;
/// the capability file and `desktop/ui/src/ipc` are tested against this list.
pub const COMMANDS: &[&str] = &[
    "snapshot",
    "start_intent",
    "service_action",
    "pair",
    "devices_revoke",
    "terminal_status",
    "terminal_grant",
    "terminal_ungrant",
    "terminal_enable",
    "terminal_disable",
    "logs_start",
    "logs_stop",
];
