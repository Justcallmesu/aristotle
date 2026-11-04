# TP1b build report — PC status card on Home (Q78)

- `ServerClient.pcStatus(token)` (GET /v1/pc-status, bearer, null on any failure) + `HttpLinkDialer.pcStatus` via new `PcStatusSource`; `AppContainer.pcStatus`.
- `data/PcStatus.kt`: model, null-tolerant `PcStatusParser`, `PcStatusFormat` (RAM GB 1 decimal, 2^30; disk GB/TB decimal; %; uptime d h / h m / m), `PcStatusView` (last value + fetch time).
- `data/PcStatusPoller.kt`: reads every 10 s only while Home is visible AND link Online; restarts with a read on return / new connection; failures keep the last value. Clock and pause are injected (fake clock in tests).
- `SessionsViewModel` owns the poller (`homeVisible()`, `SessionsUiState.pcStatus`); `SessionsScreen` drives visibility with `LifecycleResumeEffect`.
- `ui/sessions/PcStatusCard.kt`: `surfaceContainerLow`, `shapes.large`; compact row battery icon + % (+ bolt, hidden when null), CPU, RAM; tap expands (animated) to address line (the former PC line), disk, Hermes (error colour only when not running), Tailscale, uptime. Offline: values at 0.6 alpha + "as of HH:MM"; never fetched or all-null: the PC line alone. One TalkBack sentence for the compact row; expanded state is `rememberSaveable`.
- Resources: `ic_battery`, `ic_bolt`; `pc_*` strings in EN and ID. CHANGELOG: one Phone, one Server line.
- Tests: `PcStatusTest` (§16 full / all-null / wrong types / formats / mock-server call), `PcStatusPollerTest` (visibility, online, failure, new address).
- Gate: see `gate-summary.txt` in this folder.
- Not verified on a device (no adb); the card layout (FlowRow in a 360 dp width) is unseen.
