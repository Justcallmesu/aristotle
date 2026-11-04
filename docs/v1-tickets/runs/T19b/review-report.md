# T19b security review — remote terminal, phone side
Read: brief, build brief/report, protocol §14, spec Q7c/Q51–Q55, T19 red-team, the full android diff and untracked terminal files/tests. Termux v0.118.3 checked from the cached AARs (`javap -c`) plus `TerminalEmulator.java`/`TerminalOutput.java`/`LICENSE.md` fetched at tag `v0.118.3`. No gradle was run. APK `app/build/outputs/apk/debug/app-debug.apk` (07:00) lists no `libtermux.so`.

## Checks
1. **No phone shell: OK.** In the bytecode, only `TerminalSession` and `TerminalSession$3` reference `JNI`, and `JNI.<clinit>` is the only `loadLibrary("termux")`. The app never constructs `TerminalSession` or `TerminalView`. `TerminalSession` appears only as a parameter type in `SilentClient` (TerminalTab.kt:184-190). That is class loading, not initialisation, so `JNI` never initialises. The app uses `TerminalEmulator`, `TerminalOutput`, `TerminalColors`, `KeyHandler` and `TerminalRenderer`, and none of them references `JNI`. `TerminalTabTest` runs the real emulator on the JVM without the native library. With the `.so` gone, no reachable path throws `UnsatisfiedLinkError`.
2. **Answer-backs: OK.** I listed every `mSession.write` in TerminalEmulator.java. Inside `append` the emulator writes DA1/DA2, DSR, CPR, DECXCPR (:1151), DECRQM (:863, digits), CSI t window reports (:1798-1814), label reports (:1817/1820), DECRQSS (:925, echoes the query), XTGETTCAP (:1006/1012) and OSC 10-12 `rgb:` (:2088). Each reply is a single `write`. `Answerback` (Answerback.kt:11-12) lets through only digit-only CSI c/n/R/$y/t and the `rgb:` colour reply, so it drops DCS replies and label reports. A PC can therefore make the phone send only digits plus fixed punctuation, never text of its choosing. `answering` is set only around `append` (TerminalTab.kt:122-129), and all writers are on the main thread. Paste (:2585) and `sendMouseEvent` (:371) are only called by user actions, outside `append`. Other checks:
   - OSC 52 copy → `onCopyTextToClipboard` is a no-op (TerminalTab.kt:163). Its invalid-base64 log line, which quotes the payload, goes to `SilentClient`.
   - Clipboard read: `onPasteTextFromClipboard` is a no-op. The title is a no-op.
   - OSC 8 is not implemented in 0.118.3 (falls into `unknownSequence`), and the view opens no links.
   - Paste goes through `TerminalEmulator.paste`, which strips ESC/C1 so the bracket cannot be escaped (tested).
   - The clipboard is touched only by the Copy/Paste menu items (TerminalScreen.kt:86-87). Copies carry `EXTRA_IS_SENSITIVE` on API 33+ (:291-293).
3. **Keyboard/window: OK (code only; nothing run on a device).**
   - `onCreateInputConnection` (RemoteTerminalView.kt:116-120) sets `TEXT|VISIBLE_PASSWORD|NO_SUGGESTIONS` and `NO_PERSONALIZED_LEARNING|NO_FULLSCREEN|NO_EXTRACT_UI`. The editable is cleared after every commit, so the IME can read back nothing.
   - Autofill is set to `NO_EXCLUDE_DESCENDANTS`. Content capture is excluded on API 30+ (:51-52). The view exposes no accessibility text.
   - FLAG_SECURE is added while `TerminalScreen` is composed and cleared on dispose (:279-284). It stays set after `onStop`, so the recents snapshot is blank. The DropdownMenu popup inherits it. The other user of the flag (PromptSheet) is never on screen at the same time.
4. **Transport: OK.**
   - Pinning: `openTerminal` uses `clientFor(pc, endpoint)` (ServerClient.kt:82-83). That is the same pinned client as the relay: `PinnedTrustManager` + `PinnedHostnameVerifier`, `MODERN_TLS` only (so no ws://), `NO_PROXY`, no redirects (HttpClients.kt:44-57).
   - Separate socket: the URL is `https://…/v1/terminal` with a Bearer header, on its own WebSocket and not the relay.
   - No auto-reconnect: `TerminalSocket` opens once (`used` CAS), and a connection never reopens (TerminalConnection.kt:47-53, 118-126). Reopen and new tabs are user actions only.
   - Closing: leaving the page triggers `onCleared` → `close`. Going to the background closes through `onStopOrDispose` unless the activity is changing configuration (AristotleApp.kt:243-257). A network change cancels every tab (TerminalViewModel.kt:48). `changes` comes from a StateFlow, so no event is lost to the link's collector.
   - 4401 → "revoked" with no Reopen. 4403, or an HTTP 403 with a JSON body, → `turnedOff()` hides the entry for that link generation. The peer gate's text `forbidden` is not taken as a switch-off (TerminalEnd.kt:87-100).
   - Bounds: `OutputQueue` is synchronized and capped by bytes (512 KiB, drop-oldest). Paste is cut into frames of at most 16 KiB.
5. **Dependency: OK with notes.**
   - JitPack is limited by `includeGroup("com.github.termux.termux-app")` and the version is pinned to `v0.118.3`. Transitive deps: emulator ← view, plus `androidx.annotation:1.3.0` (from google()).
   - Licence: the repo's LICENSE.md at the tag says GPLv3, with the jackpal code in these two modules under Apache-2.0. That wording is ambiguous, but maintainers confirm both libraries are Apache-2.0 (termux-app issue #4257, discussion #4642). The AARs ship no LICENSE/NOTICE, and the POM says "Other".
   - AAR sha256: emulator `cbb915c4…d3e8a`, view `adb476c5…ea441`.
6. **Logging: OK.** The terminal packages make no `Log` call. Every Logger call in `TerminalEmulator` passes `mClient` (`SilentClient`), and `Logger` calls `android.util.Log` only when that client is null. The upgrade error body is peeked (512 B) and never logged. Close reasons and exit codes appear only on screen.

## Findings
- **m1 MINOR (supply chain):** `includeGroup` stops other groups being served from JitPack, but it does not stop the termux group being looked up first in google()/mavenCentral(). Also, the repo has no `gradle/verification-metadata.xml`, and JitPack serves unsigned builds that JitPack makes itself. Fix: use `exclusiveContent { forRepository { maven("https://jitpack.io") }; filter { includeGroup(…) } }` and record sha256 checksums for the two AARs and POMs.
- **m2 MINOR (licence compliance, release):** Apache-2.0 §4 requires shipping the licence text, and the app has no third-party notices screen or file. This also applies to the existing deps. Add the notices before release.
- **n1 NIT:** each tab's collector (`outputReady`, TerminalTab.kt:56; `state`, TerminalViewModel.kt:96-100) never ends. Every closed or reopened tab therefore keeps its emulator (2000-row transcript) until the screen is left. The growth is bounded by user taps, not by the PC. Fix: give each tab a Job and cancel it on close/replace.
- **n2 NIT:** if Paste runs before the emulator exists, the fallback sends the raw clipboard with no ESC/C1 stripping (TerminalTab.kt:99). This is practically unreachable because the emulator is created on first layout. Drop that paste instead of sending it.
- **n3 NIT:** a multi-line paste runs straight away when the PC program has turned bracketed paste off. Termux behaves the same, and it is a user action. Optionally confirm multi-line pastes.
- **n4 NIT:** `OutputQueue` caps bytes, not frames. With 1-byte frames the per-object overhead could reach about 15 MiB per tab. The memory is still bounded.

Not verified here: IME/FLAG_SECURE behaviour on a device and the live T19a interop (same as the build report).

VERDICT: PASS-WITH-FINDINGS
