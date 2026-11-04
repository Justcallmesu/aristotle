# Remote terminal (aristotle-server)

The Aristotle app can open a real shell on this PC (spec Q35, Q52-Q55). This page is for the PC owner: what turning it
on means, how to switch it, what the shell can and cannot do, and what to do when a phone is lost. Wire details for
the app are in `docs/protocol.md` §14.

## What enabling means
- **A granted phone gets a shell with full access to your account.** Everything your user can read or change: files,
  `~/.ssh` keys (and your running ssh-agent through `SSH_AUTH_SOCK`), `~/.hermes/.env` provider keys, browser profiles,
  the server's own TLS key (`~/.local/state/aristotle/tls-key.pem`). It can add SSH keys, cron jobs, systemd user units
  or lines in your shell rc files that keep running after the phone is gone.
- **Revoking the phone is not cleanup.** Revoke closes its terminals at once and kills their processes, but it cannot
  undo what was done from them. If a phone with a grant is lost or stolen, follow the runbook below.
- There is no app lock (spec Q7c): whoever holds the unlocked phone holds the shell. Grant only the phones you carry.
- It is off by default, and a phone needs both the switch and its own grant. Neither can be changed from the phone.

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

**From inside a remote terminal**, `pair`, `terminal enable` and `devices grant-terminal` are refused, so the phone
cannot mint itself a new pairing that outlives a revoke or widen its own access. `disable`, `ungrant-terminal`,
`revoke` and `status` stay allowed. The server recognises a terminal process by the `ARISTOTLE_TERMINAL` variable, by
its parent chain and session, and by its cgroup. **This is a speed bump, not a boundary**: a same-user shell can get
around it (for example with `systemd-run --user`, or by editing your rc files so the next desktop terminal runs the
command for it).

## The shell
- Your login shell (`$SHELL -l`, else the one in `/etc/passwd`, else `/bin/bash -l`) in your home directory, with
  `TERM=xterm-256color`. The first line on the phone says: "Aristotle terminal — sudo is unavailable (service
  sandbox); this shell has full access to your account."
- **It runs inside the aristotle-server service's sandbox** (spec Q54):
  - **No sudo**: the unit has `NoNewPrivileges=yes`, so `sudo`, `su`, `pkexec`, `passwd` and `fusermount` (sshfs,
    rclone mount) fail. This is also a safety net against a stolen phone becoming root.
  - **Private /tmp**: `PrivateTmp=yes` gives the service its own `/tmp`. `tmux attach` does not find your desktop's
    tmux sessions (their sockets live in the real `/tmp`), and X11 sockets in `/tmp/.X11-unix` are not visible.
  - **umask 077**: files the shell creates are private to you (mode 600/700) unless you change the umask.
- No reattach (spec Q55): closing the app, losing the connection or switching networks ends the shell. Limits: 30
  minutes without typing (output does not count), 8 hours in all, 2 terminals per phone, 4 in all.
- When a terminal ends, its whole session gets SIGHUP and, 5 seconds later, whatever is left is killed (revoke and
  disable kill at once). How much is caught depends on the unit:
  - **Unit with `Delegate=yes`** (installed by this version): every terminal runs in its own cgroup under the service,
    and the cgroup is killed whole (`cgroup.kill`), including `nohup`, `setsid` and daemonised processes. The log line
    `terminal cleanup method=cgroup` at server start confirms it.
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
5. **Check what the shell may have left behind** (anything added since the phone's first terminal; the server log has
   the times: `aristotle-server logs | grep 'terminal open'`):
   - `~/.ssh/authorized_keys` (and `~/.ssh/config`, `~/.ssh/rc`)
   - `crontab -l`, and `at -l` if `at` is installed
   - systemd user units: `ls -la ~/.config/systemd/user/`, `systemctl --user list-unit-files --state=enabled`,
     `systemctl --user list-timers`
   - desktop autostart: `~/.config/autostart/`
   - shell rc files: `~/.bashrc`, `~/.bash_profile`, `~/.profile`, `~/.zshrc`, `~/.zprofile`, `~/.config/fish/`
   - `~/.local/bin` and anything earlier in your `PATH` than the system directories
6. **Rotate secrets the shell could read**: Hermes provider keys in `~/.hermes/.env`, SSH keys (or at least remove the
   old public key wherever it is authorised), API and cloud tokens in your home directory, and sign out browser
   sessions if the browser profile was readable.
7. Only then grant the terminal to the new phone.
