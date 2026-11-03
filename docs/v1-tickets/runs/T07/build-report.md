# T07 build report — design system, manual pairing + TLS pin, credential store, read-only session list

**Files** (all under `android/`): theme `ui/theme/{Color,Type,Shape,Spacing,Theme}.kt` (palette A, no dynamic colour); `data/{PairingCode,ServerAddress,Fingerprint,PinnedTls,CertificateProbe,HttpClients,ServerClient,RelaySocket,RelayRpc,RpcFrames,PcCredentials,TokenCipher,KeystoreTokenKey,CredentialCodec,CredentialStore}.kt`; `ui/pair/{PairViewModel,PairScreen,FingerprintCard}.kt`; `ui/sessions/{SessionsViewModel,SessionsScreen,ConnectionPage,ConnectionIssue,SessionRows}.kt`; `ui/components/RunningSpinner.kt`; `ui/AristotleApp.kt`; `AppContainer.kt` (+`AristotleApplication`); `MainActivity.kt`. Res: `values{,-in}/strings.xml`, `values/font_certs.xml` (GMS font-provider certs, from Google's Apache-2.0 sample), `values{,-night}/{themes,colors}.xml` (window background only), `drawable/ic_{error,verified_user}.xml`, `xml/{network_security_config,data_extraction_rules,backup_rules}.xml`, `AndroidManifest.xml` (INTERNET, Application class, NSC, backup rules; `allowBackup="false"` kept). Build: `gradle/libs.versions.toml`, root + app `build.gradle.kts`. 13 test files in `app/src/test/…`. Deleted T02 placeholders `PairingState(.kt,Test)`, `ui/home/HomeScreen.kt`.

**Libraries**: okhttp **5.4.0**; kotlinx-serialization-json 1.12.0 (+ `org.jetbrains.kotlin.plugin.serialization` 2.4.21); lifecycle-viewmodel-compose **2.10.0**; ui-text-google-fonts (BOM 2026.06.01 → 1.11.4). Two pins below newest: okhttp-android 5.5.x and lifecycle 2.11.0 declare `minCompileSdk=37` and only android-36 is installed (no SDK installs). Additions with reasons: lifecycle-runtime-compose 2.10.0 (`collectAsStateWithLifecycle`, same family as viewmodel-compose). Test only: okhttp-tls + mockwebserver3 5.4.0, to generate self-signed ECDSA certs and run real TLS/WebSocket handshakes for the pin tests.

**Cleartext decision**: a network security config can't express a CIDR. So `base-config cleartextTrafficPermitted="true"` with system trust anchors only, and the rule is enforced in code: `CleartextGuard`, an OkHttp interceptor on every client (REST and the WS upgrade), throws `CleartextRefusedException` before connecting when an `http://` URL's host is not a 100.64.0.0/10 or fd7a:115c:a1e0::/48 literal. Redirects are off. Lint reports this as `InsecureBaseConfiguration` (warning).

**Design notes**
- Pinning: `PinnedTrustManager` accepts only a leaf whose SPKI SHA-256 equals the pin. `PinnedHostnameVerifier` checks the pin again instead of the host name.
- The fingerprint for comparison comes from `CertificateProbe`, a raw TLS handshake whose trust manager records the certificate and then always rejects it, so nothing is ever sent over an unverified connection. `POST /v1/pair` then goes over the pinned client, so a key swapped after the user compared fingerprints is refused.
- Addresses must be IP literals; names are refused with no DNS lookup. RFC1918 → https, tailnet → http, anything else → refused with a message.
- Credentials are keyed per PC; `pcId` = the `device_id` the PC issued. The token is sealed with Android Keystore AES-256-GCM (AAD = pc+device id). `DeviceToken.toString()` is redacted, and nothing logs.
- Active profile: from `runtime.describe` `profile_id` (`…/profiles/<name>` → name, else `default`).
- `sessions.changed` triggers a list refetch after a 1 s debounce. `srq-` server requests are ignored, since this screen subscribes to no session.

**Deviations / for PM**
- No "Scan QR" button or divider (T08).
- The 4503 page copy says "then retry" instead of "Aristotle reconnects by itself", because there is no auto-reconnect until T09.
- `session.list {limit:50}` uses Hermes' default `scope:"live"` (protocol §4), so it lists only live sessions, not the stored history the Desktop sidebar shows. Asking for `scope:"stored"` too is a PM call.
- Rows have no "last activity" field: the list only carries `started_at`, which is used (or `last_active` if Hermes ever sends it). Rows show no step N either: that needs events (T11). No design.md/mockup conflict needed a stop.

**Tests** (59, all pass):
- PairingCodeTest: normalizesLikeTheServer, sanitizeKeepsOnlyAlphabetAndCapsAtEight, completeMeansEightAlphabetCharacters, groupedIsTwoGroupsOfFour
- ServerAddressTest: lanAddressUsesHttpsAndDefaultPort, explicitPortIsKept, tailscaleAddressUsesPlainHttp, tailscaleIpv6NeedsBracketsForAPort, pastedUrlSchemeIsIgnoredTheRangeDecides, publicAndOtherRangesAreRefused, namesAndMalformedInputAreRefusedWithoutDns, badPortsAreRefused, tailnetHostCheckForTheCleartextGuard
- FingerprintTest: formatIsUppercaseHexInSixteenGroupsOfFour, parseRoundTripsAndRejectsGarbage, fingerprintIsSha256OfTheSubjectPublicKeyInfo
- PinnedTrustManagerTest: acceptsExactlyThePinnedKey, rejectsAnotherKeyAsPinMismatch, rejectsAChainWhoseLeafIsNotPinnedEvenIfAnIssuerIs, rejectsAnEmptyChainAndClientCertificates, pinMustBeASha256Digest, hostnameVerifierChecksThePinNotTheName
- PinnedConnectionTest: pinnedClientConnectsAlthoughTheCertificateNamesAnotherHost, aDifferentServerKeyIsRefusedBeforeAnyRequest, cleartextToANonTailnetHostIsRefusedBeforeConnecting, cleartextToATailnetHostPassesTheGuard, lanEndpointWithoutAPinIsAProgrammingError, probeReadsTheCertificateWithoutSendingARequest, probeReportsAPlainHttpServerAsNoCertificate, probeReportsAClosedPortAsUnreachable
- ServerClientTest: pairSendsTheNormalisedCodeInTheBodyAndReturnsTheToken, blankDeviceNameIsLeftOut, pairErrorStatusesMapToTheirStates, meSendsTheBearerToken, meUnauthorizedAndForbidden, unreachableServer
- RelaySocketTest: deliversFramesThenTheServersCloseCode, refusedUpgradeReportsTheHttpStatus
- RpcFramesTest: requestIsJsonRpc2WithIntegerId, classifiesEventsResponsesErrorsAndServerRequests, sessionListDecodesTheCapturedShapeIncludingNulls, profileNameComesFromTheProfileHomePath
- RelayRpcTest: callIsAnsweredByTheResponseWithTheSameId, errorsClosedSocketsAndTimeoutsBecomeRpcExceptions
- TokenCipherTest: roundTripsWithAFreshIvEachTime, wrongAadOrTamperingFails, wrongKeyFails
- CredentialStoreTest: savedCredentialsSurviveARestartAndTheTokenIsNotStoredInClear, wipeActiveForgetsThePc, anUnreadableEntryCountsAsNotPairedAndIsRemoved, tokenNeverAppearsInToString
- LinkOutcomesTest: closeCodesWithTheirOwnPages, everyOtherCloseIsTheGenericRetryPage, failuresWithoutACloseFrame, meDecidesWhetherToOpenTheSocket
- SessionRowsTest: rowsAreGroupedByDayWithRunningSessionsInToday, trailingTimeIsNowWhileRunningElseClockOrDate, blankTitleBecomesUntitledAndCountsPassThrough, lastActiveWinsOverStartedAtWhenPresent

**Commands**:
- `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug` → exit 0, BUILD SUCCESSFUL. Lint: 0 errors, 12 warnings: 2 CustomX509TrustManager (required pin), 1 InsecureBaseConfiguration (decision above), 8 newer-version/OldTargetApi (blocked by compileSdk 36), 1 AGP version. No suppressions added.
- `app-debug.apk` built.

**Screenshots taken**: none. `adb devices` showed no device during the whole run. mDNS lists `_adb-tls-connect` at 192.168.1.37:40723 and TCP connects, but `adb connect` fails because this adb is not paired with it, and pairing needs a code on the phone (not done: no phone settings changes).

**NOT-VERIFIED**
- Install, look and screenshots on the Poco (light/dark, 1.3× font scale).
- That downloadable Literata / Atkinson Hyperlegible Next / Mono actually load through GMS.
- End-to-end against the real `aristotle-server` + Hermes (LAN pin with the Go cert, Tailscale http, 4401/4503/4505 pages). Not run locally either: the listeners are `SO_BINDTODEVICE`-pinned to wlan0/tailscale0, so laptop-to-self traffic over lo cannot reach them.
- Android Keystore path, which runs only on a device (the JVM tests use a software AES key).
- ViewModel flows: there are no ViewModel tests (no coroutines-test dependency); only the pure pieces and the transport are tested.
- Reduced-motion static spinner.
