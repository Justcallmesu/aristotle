# T19 design draft — remote terminal (for red-team; NOT approved for build)

Status: PM draft 2026-11-04. Decisions marked **USER** need the user's answer before tickets; **ASSUMED** items need verification.

## Requirement (spec Q35)
A real shell on the laptop, opened by aristotle-server and streamed to the phone behind the device token. Server setting
`terminal.enabled`, default off.

## Proposed design
1. **Switch**: `terminal.enabled` in the server config file (`$XDG_CONFIG_HOME/aristotle/config`), default false; changed only on the PC:
   `aristotle-server terminal enable|disable` (control socket). Never toggleable from the phone. While disabled the endpoint answers 403
   `terminal_disabled`.
2. **Per-device grant (USER)**: options — (a, recommended) a device needs an explicit grant on the PC
   (`aristotle-server devices grant-terminal <id>`), so a stolen phone that was never granted cannot open a shell; (b) every paired device
   may open a terminal once enabled.
3. **Transport**: a separate WebSocket `GET /v1/terminal` on the same pinned HTTPS listeners (not the Hermes relay socket: different
   lifecycle and limits). Auth = device token as for `/v1/ws`. Binary frames for PTY bytes; small JSON text frames for control
   (`resize {cols,rows}`, `exit {code}`).
4. **PTY**: the user's login shell (`$SHELL -l`, fallback `/bin/bash -l`) in `$HOME`, started via `/dev/ptmx` with std lib syscalls
   (ASSUMED feasible without a dependency; fallback `github.com/creack/pty`, MIT). Env: inherit the service env minus aristotle's own vars,
   `TERM=xterm-256color`. Process group killed (SIGHUP then SIGKILL after 5 s) when the socket closes, the device is revoked (close 4401),
   the server shuts down, or terminal is disabled.
5. **Limits**: max 2 terminals per device, 4 total; idle timeout 30 min without input or output (USER may change); output backpressure:
   pause reading the PTY when the socket's send buffer is full (no unbounded buffering); frame cap 64 KiB.
6. **Logging**: open/close/exit/revoke events with device id, pid, duration, byte counts. Never terminal content or keystrokes.
7. **Reattach (USER)**: (a, recommended v1) no reattach — closing the socket (app killed, network switch) ends the shell; (b) keep the shell
   alive N minutes for reattach (tmux-like; more state, a shell left running unattended).
8. **Phone**: a Terminal screen per PC: emulator view (ASSUMED: Termux `terminal-view`/`terminal-emulator` libraries, Apache-2.0, as an
   Android View inside Compose), extra-keys row (Esc, Tab, Ctrl, arrows), resize on rotation/keyboard; FLAG_SECURE optional (USER).
   Shown only when the server reports terminal enabled AND this device is granted (`/v1/me` capability flag).
9. **Network switch**: with (7a) the shell ends on a Wi-Fi/Tailscale switch; the phone shows "Terminal closed (connection changed)" with
   Reopen.

## Red-team questions
- Can any path start a shell while `terminal.enabled=false` or for an ungranted device (races with disable/revoke)?
- Is the device token alone enough given Q7c (no app lock)? What does a lost unlocked phone give an attacker, and what mitigations are
  cheap (grant per device, revoke, short idle timeout, notification on the PC when a terminal opens)?
- PTY process cleanup: orphaned children (background jobs, `nohup`, `setsid`) — what survives SIGHUP, and is that acceptable?
- Backpressure and memory under `yes` / `cat /dev/urandom`.
- systemd user service sandboxing (T21 unit: NoNewPrivileges, PrivateTmp) — does it break `sudo` in the shell or the user's expected
  environment? Should the terminal note that?
- Env leakage: does the service env carry anything sensitive (Hermes credentials, aristotle state paths) into the shell?
- Logging: confirm nothing in the planned log lines can contain typed secrets.
