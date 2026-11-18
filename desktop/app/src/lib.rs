//! `aristotle-desktop`: the window app. Thin IPC commands over `aristotle-core`; no logic copied from it.
//!
//! One module per concern: [`args`] (start intent), [`backend`] (shared state + the P5/P13 guard),
//! [`dto`] (what crosses to the frontend), [`service`], [`pairing`], [`devices`], [`terminal`], [`logs`].

pub mod args;
pub mod backend;
pub mod devices;
pub mod dto;
pub mod ipc;
pub mod logs;
pub mod pairing;
pub mod service;
pub mod terminal;

mod command_list;
pub use command_list::COMMANDS;

#[cfg(test)]
mod testing;
