# Changelog

All notable changes to Aristotle (the Android app and the PC server) are listed here, newest first.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

Each entry starts with `Phone:` (the Android app) or `Server:` (the program on your PC). The app's "What's new" page is built from this
file at build time and shows the `Phone:` entries only.

## [Unreleased]

### Added

- Server: Stocks for the phone (`/v1/stocks/*`): your IDX watchlist, holdings with profit and loss after broker fees, delayed prices and charts from Yahoo, and market news from CNBC Indonesia, Kontan and IDX Channel tagged with your stocks.
- Phone: Find your PC again by itself when you join a Wi-Fi where its address has changed; only your paired PC, checked by its certificate, is accepted.
- Phone: "Find PC on this Wi-Fi" in Settings › Connection looks for your PC on the Wi-Fi you are on when it was not found by itself.
- Phone: Settings › Connection shows your saved PC addresses (where each came from, when it last worked, delete) and a connection history of the last 7 days.
- Phone: Above the message field, a chat lists its running subagents and background processes; a process can be stopped after you confirm.
- Server: Announce itself on your Wi-Fi (never over Tailscale) so the phone can find it after a network change.
- Phone: Home shows your PC's battery, CPU and memory under the title (tap for disk, Hermes, Tailscale and uptime), refreshed every 10 seconds while Home is open and the PC is reachable.
- Phone: Long-press a message to copy, share, select, quote it or steer with it; every code block has a copy button.
- Phone: Settings › About › "Send debug log" shares a privacy-filtered log through the share sheet; after a crash the app offers to share a report on the next start. Nothing is sent automatically.
- Server: Report the PC's battery, CPU, memory, disk, Hermes and Tailscale state to the phone (read-only, `GET /v1/pc-status`).
- Phone: Home shows your Claude session, week, Opus and Sonnet limits as four thin bars under the PC card (tap for reset times; red from 90%), refreshed at most every 5 minutes while Home is open.

## [1.0.0] - 2026-11-04

First release.

### Added

- Phone: Pair with your PC by scanning a QR code or typing the pairing code.
- Phone: See your live Hermes sessions on top and your stored history below; search, pin, rename and start new sessions.
- Phone: Switch between Hermes profiles and see each profile's scheduled jobs.
- Phone: Read a chat as it streams, with Markdown, compact tool-call groups and expandable cards for finished background work.
- Phone: Send messages, and Steer, Queue or Stop a turn that is still running.
- Phone: Answer approvals and questions from Hermes in the chat or straight from the notification.
- Phone: Change the model and reasoning level, and use Hermes slash commands.
- Phone: Send pictures and files; location data is removed from photos before they leave the phone.
- Phone: Watch subagents work and stop them.
- Phone: View, pause, resume and remove Hermes scheduled jobs.
- Phone: Open a shell on your PC from the phone, only when the PC has allowed it and Show Terminal is on.
- Phone: Get notifications while the app is in the background, with a guide for keeping it running on Xiaomi phones.
- Phone: Choose light, dark or the phone's theme, the text size, and English or Indonesian.
- Phone: Warn when the app and the PC server are too far apart to work together; About shows both versions.
- Phone: A quote from Aristotle appears while the app connects.
- Phone: "What's new" under About, and once after each update.
- Server: Run on your PC as a background service (`aristotle-server install`) that connects the phone to Hermes.
- Server: Reach the PC over Wi-Fi at home or over Tailscale away from home.
- Server: Pair phones with a code or QR, list paired phones, and revoke any of them.
- Server: Optional remote terminal, off until you turn it on and grant it to a phone.
- Server: Report its version and protocol version so the app can check compatibility.

### Security

- Phone: Every connection uses HTTPS pinned to your PC's certificate; there is no plain-text path.
- Phone: The pairing token is stored encrypted and is left out of backups and device transfers.
- Phone: Optional app lock with fingerprint or screen lock, plus extra checks before the terminal and risky approvals.
- Phone: The terminal screen blocks screenshots and screen recording.
- Phone: The QR scanner's analytics components are removed.
- Server: Phones must sign in with their own revocable token; too many wrong pairing attempts lock that address out.
- Server: Hermes' system prompt is never sent to the phone.
- Server: The terminal is off by default and needs two separate steps on the PC to enable.
- Server: Connections are size- and rate-limited, and each request is written to an audit log.
