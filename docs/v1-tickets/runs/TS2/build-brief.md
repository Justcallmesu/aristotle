# TS2 build brief — app lock + hide-terminal switch (Q61, Q62), with TS1b

route: deep (security). Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q7c (superseded), Q50, Q51, **Q61, Q62**,
`docs/design.md`, `docs/v1-tickets/runs/TS1/review-report.md` (TS1b items), then `ui/settings/*`, `data/{AppSettings,PcRecords,
CredentialStore}.kt`, `ui/pair/*`, `ui/chat/{ChatViewModel,PromptSheet*}.kt`, `ui/terminal/*`, `MainActivity.kt`, `ui/AristotleApp.kt`.

## TS2 (Q61, Q62)
1. **App lock** (Settings › Security, off by default): turning it on requires a successful BiometricPrompt with
   `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (androidx.biometric — add the dependency, latest stable; works without AppCompat? verify:
   BiometricPrompt needs a FragmentActivity — if MainActivity is a ComponentActivity, switch it to FragmentActivity, nothing else). If the
   device has no lock screen set, the switch explains and stays off.
2. **When it locks:** on open/return after N minutes in the background (choice: Immediately, 1, 5, 15 min; default 1), the app shows a lock
   screen (palette, icon, "Unlock") over everything; content is not composed underneath (no peek in recents: also FLAG_SECURE on the lock
   screen). Process death counts as locked.
3. **Also required when on** (fresh auth, not the timeout): opening the terminal, "Always allow" confirm, sending a sudo or secret value.
   An auth failure/cancel sends nothing. Keep the 600 ms arm. Never auth from the lock screen / notifications (Q50 unchanged).
4. **Hide Terminal on this phone** (Q62): a switch in Security; when off the Terminal entry is hidden everywhere (⋮ menu) regardless of
   `/v1/me`. Default on (shown when the PC allows it).
5. Notification content is unchanged (already generic on the lock screen).

## TS1b (from runs/TS1/review-report.md)
6. Back during a busy re-pair cancels it (a late success must not replace the old pairing).
7. Re-pair/unpair storage writes happen in one commit (no orphaned encrypted token after a kill); unpair clears drafts/settings BEFORE the
   credentials so a kill in between can't leave drafts for a re-pair.
8. Unpair cancels the old PC's posted notifications.

## Tests
Lock policy (timeout choices, process death, return timing) as pure logic; gating of terminal/Always allow/sudo/secret behind a fake
authenticator (failure sends nothing); hide-terminal visibility rule; TS1b cases (cancel, atomic writes order).

## Hard rules
Files: `android/**` (not `ui/theme/**`), `docs/v1-tickets/runs/TS2/build-report.md`. strings.xml append-only. A server worker (TL1a)
runs in parallel: do not touch `server/**`. No commits; never git checkout/restore/stash/reset. No adb.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TS2` exits 0.

## Report
`docs/v1-tickets/runs/TS2/build-report.md`, ≤25 lines.
