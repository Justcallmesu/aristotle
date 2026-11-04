# Remote terminal (aristotle-server)

The Aristotle app can open a real shell on this PC (spec Q35, Q52-Q55). This page is for the PC owner: what turning it
on means, how to switch it, what the shell can and cannot do, and what to do when a phone is lost. Wire details for
the app are in `docs/protocol.md` §14.

## What enabling means
- **A granted phone gets a shell that is your user account.** Everything your user can read or change: every file,
  `~/.ssh` keys, `~/.hermes/.env` provider keys, browser profiles, the server's own TLS key
  (`~/.local/state/aristotle/tls-key.pem`). It can add SSH keys, cron jobs, systemd user units or lines in your shell
  rc files that keep running after the phone is gone.
- **It can reach your live desktop session.** The shell starts without `DISPLAY`, `WAYLAND_DISPLAY`,
  `DBUS_SESSION_BUS_ADDRESS`, `SSH_AUTH_SOCK` and the other session variables (see "The shell"), but it can set them
  itself: X listens on abstract sockets (`@/tmp/.X11-unix/X0`) that the service's private `/tmp` does not hide, and
  the Wayland socket, the session bus and usually your ssh-agent live in `$XDG_RUNTIME_DIR`. So it can capture the
  screen, read the clipboard, type into an unlocked desktop and use your loaded SSH keys.
- **The in-shell checks are speed bumps, not a boundary.** The refusal of `pair`, `terminal enable` and
  `grant-terminal` from a terminal, and the cleanup cgroup, stop accidents and casual misuse. A same-user shell gets
  around both in one command: `setsid -f env -u ARISTOTLE_TERMINAL aristotle-server pair` (a new session without the
  marker; without a cgroup this passes every check), or `echo $$ > <the service's cgroup>/cgroup.procs` (the cgroup
  files under your user manager are yours), after which neither the check nor the cleanup sees it. It can also run
  the command through your desktop (the session bus, a desktop terminal, your rc files).
- **The real controls** are: the terminal is off by default; each phone needs its own grant, typed on the PC; revoke,
  ungrant and disable close every terminal at once; terminals end after 30 minutes without typing; and the lost-phone
  runbook below. Revoking a phone is not cleanup: it cannot undo what was done from its terminals.
- There is no app lock (spec Q7c): whoever holds the unlocked phone holds the shell. Grant only the phones you carry,
  and disable the terminal while you do not need it.

## Commands (on the PC)
```
aristotle-server terminal enable            # switch on (state: ~/.local/state/aristotle/terminal.json)
aristotle-server terminal disable           # switch off: every open terminal closes, its processes are killed
aristotle-server terminal status            # switch, granted devices, open terminals (id, device, since, pid)
aristotle-server devices list               # find the device id
aristotle-server devices grant-terminal <id>
aristotle-server devices ungrant-terminal <id>   # closes that device's terminals
aristotle-server devices revoke <id>             # also removes its grant
```
The server must be running (they talk to it over its control socket). Opening and closing terminals is logged
(`aristotle-server logs | grep terminal`): device, terminal id, pid, duration, byte counts and how it ended. Never what
was typed or shown; the shell's own history file (`~/.bash_history` etc.) does keep typed commands, as on any shell.

**From inside a remote terminal** only these commands run: `status`, `devices list`, `terminal status`, `terminal
disable`, `devices ungrant-terminal` and `devices revoke`. Everything else is refused, including `pair`, `terminal
enable`, `devices grant-terminal` and any command added later, so the phone cannot mint itself a pairing that outlives
a revoke or widen its own access by accident. (`logs` reads the journal directly and works anywhere.) The server
recognises a terminal process by the `ARISTOTLE_TERMINAL` variable, by its parent chain and session, and by its
cgroup. **This is a speed bump, not a boundary** (see above): a same-user shell can get around it.

**If `terminal.json` is unusable** (corrupt, or not mode 600), the server still starts and the relay works; the
terminal stays disabled with no grants, the log has an error line, and `terminal status` says what is wrong. Fix the
file (`chmod 600`) or remove it (that also removes every grant), then run `terminal enable` again. Until then
`terminal enable` and `grant-terminal` are refused and the file is left as it is.

## The shell
- Your login shell (`$SHELL -l`, else the one in `/etc/passwd`, else `/bin/bash -l`) in your home directory, with
  `TERM=xterm-256color`. The first line on the phone says: "Aristotle terminal — sudo is unavailable (service
  sandbox); this shell has full access to your account."
- **Desktop session variables are removed**: `DISPLAY`, `WAYLAND_DISPLAY`, `XAUTHORITY`, `DBUS_SESSION_BUS_ADDRESS`,
  `XDG_SESSION_*`, `XDG_SEAT*`, `SWAYSOCK`, `HYPRLAND_INSTANCE_SIGNATURE`, `I3SOCK`, `SSH_AUTH_SOCK`,
  `GPG_AGENT_INFO`, `DESKTOP_SESSION` and `XDG_CURRENT_DESKTOP` (and systemd's own, such as `NOTIFY_SOCKET`), so
  programs do not open windows on, or talk to, your desktop by default. This is hygiene, not isolation (see "What
  enabling means"). To keep some of them, list them in the server config (`~/.config/aristotle/config.json`, then
  restart the service); systemd's and Aristotle's own variables cannot be kept:
  ```
  { "terminal": { "keep_env": ["SSH_AUTH_SOCK"] } }
  ```
- **It runs inside the aristotle-server service's sandbox** (spec Q54):
  - **No sudo**: the unit has `NoNewPrivileges=yes`, so `sudo`, `su`, `pkexec`, `passwd` and `fusermount` (sshfs,
    rclone mount) fail. This is also a safety net against a stolen phone becoming root, though the shell can still
    ask your desktop session to run something outside the sandbox.
  - **Private /tmp**: `PrivateTmp=yes` gives the service its own `/tmp`. `tmux attach` does not find your desktop's
    tmux sessions (their sockets live in the real `/tmp`). It does not hide X: X11's abstract sockets stay reachable.
  - **umask 077**: files the shell creates are private to you (mode 600/700) unless you change the umask.
- No reattach (spec Q55): closing the app, losing the connection or switching networks ends the shell. Limits: 30
  minutes without typing (output does not count), 8 hours in all, 2 terminals per phone, 4 in all.
- When a terminal ends, its whole session gets SIGHUP and, 5 seconds later, whatever is left is killed (revoke and
  disable kill at once). How much is caught depends on the unit:
  - **Unit with `Delegate=yes`** (installed by this version): every terminal runs in its own cgroup under the service,
    and the cgroup is killed whole (`cgroup.kill`), including `nohup`, `setsid` and daemonised processes. The log line
    `terminal cleanup method=cgroup` at server start confirms it. A process that moves itself out of that cgroup
    (the files are yours to write) is not killed; `systemd-run --user` and the desktop are outside it too.
  - **Older unit, or `serve` run by hand**: background jobs, `nohup` and `disown` are still killed (they stay in the
    shell's session), but anything started with `setsid` or as a daemon survives until the service stops. The log says
    `method=session` at start and warns at each close.
  - A unit installed before the terminal existed lacks `Delegate=yes`, and `install` will not overwrite it silently:
    run `aristotle-server install --force`, then `systemctl --user restart aristotle-server` (a running service keeps
    its old settings until it restarts).
- Stopping or restarting the service ends every terminal and kills everything left in its cgroup.

## Lost or stolen phone: runbook
Do these in order, on the PC.
1. **Revoke the phone**: `aristotle-server devices list`, then `aristotle-server devices revoke <id>`. Its relays and
   terminals close at once and their processes are killed; its token and grant are gone.
2. **Turn the terminal off**: `aristotle-server terminal disable` (re-enable later for your new phone).
3. **Look for devices you do not know** in `devices list` and revoke them too.
4. **Rotate the certificate** (a terminal could have copied the TLS key and could then pose as your PC to your other
   phones). There is no `cert rotate` command yet; do it by hand:
   ```
   systemctl --user stop aristotle-server
   mkdir -p ~/aristotle-old-cert && mv ~/.local/state/aristotle/tls-cert.pem ~/.local/state/aristotle/tls-key.pem ~/aristotle-old-cert/
   systemctl --user start aristotle-server     # creates a new key pair; the fingerprint changes
   ```
   Every remaining phone must then pair again (`aristotle-server pair`): the old pin no longer matches. Revoke their
   old device entries.
5. **Look for processes the shell left running**: `systemctl --user status aristotle-server` (once its terminals
   are closed, anything in the service's cgroup besides `aristotle-server serve`, or in an `aristotle-term-…`
   sub-cgroup), `pgrep -u $USER -a` for anything you did not start, and `systemctl --user list-units
   --type=service,scope` for units started with `systemd-run`. Kill what you do not recognise; stopping the service
   kills everything still in its cgroup.
6. **Check what the shell may have left behind** (anything added since the phone's first terminal; the server log has
   the times: `aristotle-server logs | grep 'terminal open'`):
   - `~/.ssh/authorized_keys` (and `~/.ssh/config`, `~/.ssh/rc`)
   - `crontab -l`, and `at -l` if `at` is installed
   - systemd user units: `ls -la ~/.config/systemd/user/`, `systemctl --user list-unit-files --state=enabled`,
     `systemctl --user list-timers`
   - desktop autostart: `~/.config/autostart/`
   - shell rc files: `~/.bashrc`, `~/.bash_profile`, `~/.profile`, `~/.zshrc`, `~/.zprofile`, `~/.config/fish/`
   - `~/.local/bin` and anything earlier in your `PATH` than the system directories
7. **Rotate secrets the shell could read**: Hermes provider keys in `~/.hermes/.env`, SSH keys (or at least remove the
   old public key wherever it is authorised, and anything your ssh-agent held), API and cloud tokens in your home
   directory, and sign out browser sessions if the browser profile was readable.
8. Only then grant the terminal to the new phone.
