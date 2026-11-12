# Changelog

All notable changes to Aristotle (the Android app and the PC server) are listed here, newest first.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

Each entry starts with `Phone:` (the Android app) or `Server:` (the program on your PC). The app's "What's new" page is built from this
file at build time and shows the `Phone:` entries only.

## [Unreleased]

## [2.2.0] - 2026-11-12

### Added

- Server: `./install.sh` at the top of the repo installs the newest release folder in `dist/` (or the one you name with `--bundle <dir>`).
- Server: The installer now finishes the whole setup: it starts the Hermes gateway when it is not running (`--no-gateway` to skip), keeps the services running after you log out, and opens the firewall (ufw or firewalld) for your local network only, asking for your sudo password (`--no-firewall` to only print the commands). It reports success only when the server can reach Hermes.
- Server: `aristotle-server status` shows whether Hermes is reachable; `status --porcelain` prints the same facts for scripts.

## [2.1.1] - 2026-11-09

### Fixed

- Server: Every file in a release folder is readable again (the licence notices came out readable only by you).

## [2.1.0] - 2026-11-09

### Added

- Phone: The app updates itself from your PC. Settings › About shows "Update x.y.z available" (and an "App updates" notification, once per version). Tap Update to download it over your paired connection, checked against its checksum and the app's own signature, then install it. Android asks once to allow installs from Aristotle, and may ask you to confirm the install; the link reconnects by itself afterwards when "Keep connected in background" is on.
- Server: `install.sh` also hands the release's phone app to the server, which offers it to paired phones (`GET /v1/app/update`, `GET /v1/app/apk`).

## [2.0.0] - 2026-11-08

### Added

- PC: Releases come as one folder per version: the signed phone app, the server, an `install.sh` that checks the files, installs or updates the server (keeping the previous one for a rollback), waits until it is up and can show the pairing QR, plus the licence notices and release notes.
- Phone: Settings › About › Open-source licences lists every library and font in the app with its licence text.
- Phone: AI track record: Stocks ⋮ → AI track record shows, per verdict type, how often the AI's calls hit (Buy today / Add: target, Hold: stop held, Avoid / Sell: right) with filters by model, trigger and confidence, and a "How scoring works" sheet; the verdict card says how that verdict type has done; a stock's page lists its past calls with result chips.
- Server: AI track record: every kept stock verdict is logged and scored after the close (17:00 job `track.score`, using that session's 5-minute prices) — Buy today/Add as win, loss, closed flat or not triggered, Hold by its own stop and target, Avoid/Sell right or wrong, Wait as "would have made", plus a 3-day result for information; `GET /v1/stocks/track-record` gives the counts per verdict, model, trigger and confidence.
- Phone: Stock alerts arrive as notifications, one per stock, with nothing readable on the lock screen. With Keep connected off they arrive only while the app is open; missed ones are listed under Stocks › Alerts. The 08:30 brief has a card on Stocks home and its own page with the last 7 days. Holdings take an optional stop and target. Settings › Stocks shows the alerts switch and today's automatic analyses (at most 5).
- Server: Stock alerts for the phone (`/v1/stocks/alerts`, read by long-poll; missed alerts wait on the PC): your own stop and target on a holding, the entry zone, target and stop of today's analysis, setups firing on your watchlist and holdings, and strong news about them from 08:00 to 16:30; at most 3 a stock and 30 a day are notified, quieter outside market hours.
- Server: Automatic analyses: when a stock on your watchlist or in your holdings shows two setups on one day, the PC analyzes it on Sonnet (at most 5 a day, once per stock), keeps the result like Analyze and sends an alert.
- Server: The 08:30 brief (`/v1/stocks/brief`) on trading days: overnight news, yesterday's screen, your holdings and kept verdicts summed up by the AI (Opus) in your app's language, with levels shown from the PC's own numbers; it runs late until 09:00 if the PC was asleep, and can be rerun on request.
- Server: Holdings take an optional stop and target (`stocks-levels.json`; `stocks.json` is unchanged).
- Phone: Stock screener — Stocks home has a Screener section under your watchlist with the top setups. "See all" opens the list for Today or the last close. You can filter it by setup, see each stock's labels (Breakout 20d, Vol ×2,3, MA cross, RSI, Gap) and its score, and bearish flags on your own stocks, and you can Scan now. Your watchlist and holdings show the setup that fired. Settings › Stocks › Screener lets you turn each setup on or off, set its thresholds, the liquidity floor and the scan interval, and edit the LQ45/IDX80 list.
- Server: Stock screener on your PC (`/v1/stocks/screener`): every 10 minutes during the sessions it checks the LQ45/IDX80 list and your watchlist for a breakout above the 20-day high with volume, a volume spike, an MA20/50 cross, an RSI rebound and an opening gap, ranks the liquid ones (at least Rp 5 bn a day), marks gap-down and MA cross-down on your watchlist and holdings, screens each close after the 16:20 price download, and keeps the thresholds you set (`stocks-screener.json`); "scan now" on request.
- Server: Stocks jobs on your PC (`/v1/stocks/jobs`): a scheduler that keeps working across sleep and catches up missed jobs at start, a daily 16:20 download of a year of daily prices for the LQ45/IDX80 list, your watchlist and holdings (`stocks-bars.json`), and an editable LQ45/IDX80 list (`/v1/stocks/universe`, `stocks-universe.json`).
- Phone: Analyze on a stock's page runs the AI analyzer on your PC (shows progress, you can leave the page) and shows a verdict card — Buy today / Wait / Avoid or Add / Hold / Sell, confidence, entry zone, target and stop with % from the price, reasons with their news sources, data time, and "Analysis only, not financial advice".
- Phone: A stock's page: price chart in candles or line for 1 day to 1 year with volume (touch and hold to read any bar), today's numbers, your holding with profit and loss after fees, news about the stock, add or remove it from the watchlist, and "Open in Ajaib".
- Phone: Stocks on Home: tap "Stocks" in the title to see your IDX watchlist with today's moves, your holdings with profit and loss after broker fees, and market news; add, reorder and remove stocks, edit holdings, and set the broker fees in Settings › Stocks.
- Server: Stock analyzer for the phone (`/v1/stocks/analyze`, `/v1/stocks/analysis`): computes trend, moving averages, RSI, volume, support and resistance and today's limits on your PC, then asks Hermes (profile `aristotle-stocks`, no tools) for a checked buy / wait / avoid or add / hold / sell verdict with entry, target, stop and cited reasons; the last result per stock is kept.
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

### Fixed

- Server: `install --force` now restarts a running service so the new binary is live; a symlinked unit file is never replaced (install refuses and names its target); `status` exits non-zero unless the service is active and answering.
- Server: Screener: a scan no longer undoes a settings change made while it ran; empty (`null`) settings are refused; a stock without a trade yet today keeps its signals; the opening gap is read again when the first try came too early, and no longer pushes your charts out of the cache.
- Server: The service no longer refuses every control command from your PC terminal (`pair.start` and others); the unit has no `PrivateTmp` any more, so run `aristotle-server install --force` and restart the service.

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
