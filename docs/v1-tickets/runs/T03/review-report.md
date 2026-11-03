# T03 review — server skeleton + gates.sh

Reviewer: fresh context. Evidence: `go vet`, `gofmt -l`, `go test -race -count=1 ./...` all clean in `server/`.
gates.sh run on a scratch copy (`cache/scratch/t03-review/`, sources untouched, `diff -r` identical) with injected
failures; live binary run (temp XDG dirs, port 18761).

## Verified OK
- gates.sh FAIL paths: gofmt fail -> `FAIL server-gofmt (1)`, exit 1; failing test -> `FAIL server-test (1)`, exit 1;
  compile error -> vet+test FAIL, exit 1; failing gradlew -> `FAIL android (1)`, exit 1. Later steps still run and the
  summary is always written. Runs from `/` and from another dir with a relative run-dir. Writes only into the run-dir.
- Live: `-X main.version=9.9.9` works; `/healthz` -> `{"status":"ok","version":"9.9.9"}`; `ss` shows `127.0.0.1:18761`
  only; SIGINT -> "shutting down", exit 0 (closes the build report's NOT-VERIFIED SIGINT item); new state dir 0700; Authorization
  header and query token absent from the log; `{"host":"::"}` -> clear error, exit 1; `bogus` -> usage on stderr, exit 2.
- Relative `XDG_STATE_HOME` is ignored and falls back to `~/.local/state/aristotle` (follows the XDG spec; checked with a probe).
- Code shape matches PROJECT.md: thin main, three packages with one role each, small named functions, std lib only.

## Findings
1. MAJOR — `server/internal/config/config.go:103` `os.MkdirAll(dir, 0o700)` does not change an existing directory.
   Probe: a pre-existing `aristotle/` state dir at 0755 stays `rwxr-xr-x`, and `EnsureStateDir` returns nil. This
   matters because T05 will keep the TLS private key and device-token hashes in this dir. Under Q26 (systemd user
   service), a later `StateDirectory=aristotle` would create the dir as 0755 by default. T05 and T21 would then rely on
   a false fact ("state dir is 0700"). Fix: after MkdirAll, `os.Chmod(dir, 0o700)`, or refuse to start when
   `mode&0o077 != 0`. Add a test with a pre-created 0755 dir.
2. MINOR — `docs/v1-tickets/gates.sh:48` `[[ -x android/gradlew ]]`: if gradlew exists but is not executable, the step
   is skipped silently and the script exits 0 (proven in probe E). The tree is a fresh repo, and a checkout with
   `core.fileMode=false` or a zip extract can drop the exec bit, which turns the Android gate into a false green.
   Fix: test `-f`, then run `bash ./gradlew …`, or FAIL when `android/` exists but gradlew cannot run.
3. MINOR — `config.go:52-53` `DisallowUnknownFields` still matches keys case-insensitively, and the last duplicate
   key wins. `{"HOST":"127.0.0.1","Port":9001}` and `{"port":9001,"port":9002}` are both accepted (probe).
   The loopback check still holds, so this is not a security hole. It does weaken the "unknown keys = error" claim.
   Fix: decode into `map[string]json.RawMessage` first and check exact key names, or document the behaviour.
4. MINOR — `server/internal/app/app.go:54-55` `stop()` from `signal.NotifyContext` is deferred to the end of
   `runServe`, so a second Ctrl-C during the shutdown window (up to 5 s) is swallowed. Later, with long-lived WS
   relays, the window will matter more. Fix: call `stop()` as soon as `ctx.Done()` fires (e.g. pass `stop` in, or use
   `context.AfterFunc`) so a second signal kills the process.
5. NIT — `gates.sh:41-43` counts only `--- PASS/FAIL` lines. With a compile error the summary says
   `go-tests passed=3 failed=0` (probe D), although `FAIL server-test` is on the line above. Optionally also count
   `^FAIL\s.*\[build failed\]`.
6. NIT — `config.go:54` an empty config file gives `invalid config: EOF`. A clearer message, or treating empty as
   defaults, would help the user.
7. NIT — `app.go:86` the `http.Server` has no `ErrorLog`, so net/http errors go to the std logger, not slog. Note for
   T06: `Shutdown` does not wait for hijacked (WebSocket) connections. Use `RegisterOnShutdown`, or track the
   connections and close them.
8. NIT — `server/internal/httpapi/httpapi.go:37` logs `r.URL.Path`. This is safe today. It becomes a token leak if a later route puts a
   pairing code or token in the path. Add a comment or rule: secrets never go in paths.
9. NIT — tests: `server/internal/config/config_test.go:68` ignores the `ConfigPath` error. The home fallback (unset XDG) is not
   unit-tested. `Serve` (config + state dir + listen) is only covered by the manual run, because `serveListener`
   is tested in isolation. The required tests are all present and they test real behaviour (the log-leak test covers
   the query, header and body).

## Not run
- The full `gates.sh` against the real repo (the Android step takes minutes). The PM gate `pm-gate-1` already shows it GREEN. shellcheck is not
  installed.

VERDICT: PASS-WITH-FINDINGS
