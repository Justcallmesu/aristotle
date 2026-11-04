# TR7 build report — PC status card option A "Meters"

- `PcStatusCard.kt` rebuilt per design-notes §5.7: Meter (label 12 / value 16 sp / 4 dp determinate `LinearProgressIndicator`, `primary` on `surfaceContainerHighest`, `outline` bars + muted values when stale), 2-column `surfaceContainer` tiles (Disk + bar, Uptime, Hermes, Tailscale; address full width, wifi/vpn icon by `network`), as-of line, red Hermes-down line + tile; null fields hide their meter/tile; uptime text if all three meters are null.
- Toggle row ≥ 48 dp, one TalkBack sentence ("PC status. Battery …, processor …, memory … used. Hermes not running. Values as of HH:MM."). Expanded state is the existing `rememberSaveable` in `SessionsScreen` (survives rotation).
- `SessionsScreen.kt`: only the call changed (`network = (state.link as? LinkState.Online)?.endpoint?.network`).
- `PcStatus.kt`: `PcStatusFormat.batteryFraction / cpuFraction / ramFraction / diskUsedFraction` (clamped 0..1, null-safe); tests in `PcMeterFractionTest.kt`.
- Strings: 17 new keys appended to `values` and `values-in` (ID translated). Old `pc_cpu_value`/`pc_ram_value`/`pc_disk_value`/`pc_as_of` now unused, left in place (append-only).
- Icons: `ic_hard_drive`, `ic_timer`, `ic_vpn_lock`, `ic_wifi`, `ic_history` — hand-drawn stroke vectors in the Outlined-400 style, not the exact Google glyph paths; swap in the official paths if exact glyphs matter.
- Not done: Compose UI tests from §5.7 (the repo has no Compose test setup); no on-device/visual check (no adb).
- Gate: `GATE_ONLY=android … gates.sh` → PASS (exit 0). An earlier full run failed on `RepairTest` (passes alone and on rerun) and a lint-tool crash on `CrashReportsTest.kt`, both unrelated and not reproduced.
