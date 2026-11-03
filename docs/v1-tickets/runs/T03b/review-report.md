# T03b review — server state dir, strict keys, gate false green

Reviewer: fresh context. I checked `git diff HEAD -- server docs/v1-tickets/gates.sh`. In `server/`, `gofmt -l` printed nothing, `go vet` exited 0 and
`go test -race -count=1 ./...` exited 0. I ran probe tests and planted gate failures on a scratch copy (now deleted). Sources and git state are untouched.

## Previous review (T03) findings
1 MAJOR state dir 0700: FIXED. 2 MINOR gate false green: FIXED. 3 MINOR case/duplicate keys: FIXED. 5 NIT build-failed
summary: FIXED. 6 NIT empty file: FIXED. 8 NIT path logging: PARTIAL (see M1). 4, 7, 9: DEFERRED as the brief allows. They are listed in the build report.

## Verified OK
- State dir: a new dir gets 0700, an existing 0755 dir is tightened, and a symlink is refused (tests). Probe: `ensureDir("/usr")` and `"/tmp"` give
  `owned by uid 0 ... refused`, so the untested other-owner path works. A dangling symlink is refused because MkdirAll gets EEXIST.
- Keys (probes): `HOST`, `Port` and `hoſt` (Unicode fold) give "must be written exactly as". `"\u0068ost"`+`host` and `port`×2 give duplicate.
  Whitespace around tokens is accepted. `" host"` gives unknown field. A nested object as a value gives a decoder type error. Trailing data, a trailing comma and `[]`
  are all rejected. checkKeys runs before the decoder, so the decoder's case-folding never applies.
- gates.sh (scratch): a non-exec gradlew gives `FAIL android (126)`, exit 1. A vet error inside `go test` gives `[build failed]`, then `unknown`. A missing go.mod
  gives `[setup failed]`, then `unknown`, exit 1. A panicking test gives `failed=1`. A missing gradlew gives SKIP. `android/gradlew` is tracked 100755.
- Route log: `GET /healthz?a=b` logs `route="GET /healthz"`, and the query never appears (test plus probe). PM gate `pm-gate-2` is GREEN.

## Findings
M1. MINOR: `server/internal/httpapi/httpapi.go:47`. The fallback `return r.URL.Path` logs the full client path for every
    unmatched request. Probe: `GET /pair/SECRET123?tok=abc` (404) logs `route=/pair/SECRET123`. The new comment says secrets
    never go in paths, but a 404 path is chosen by the client: a typo'd or old pairing URL would land in the log. This is the
    case NIT 8 warned about. Fix: log a constant (`route=unmatched`, plus the status if wanted) when `r.Pattern == ""`.
    Add a test with a 404 path.
M2. MINOR (unproven, low risk): `config.go:171-179`, TOCTOU between `os.Lstat` and `os.Chmod`. `os.Chmod` follows symlinks,
    so if the dir is swapped for a symlink in that window, chmod hits the target. Exploiting this needs write access to the parent
    (`~/.local/state`, owned by the user), so an attacker would already be the user. Not a real hole on this laptop.
    Std-lib fix if wanted: `syscall.Open(dir, O_RDONLY|O_DIRECTORY|O_NOFOLLOW)`, then `f.Stat()`/`f.Chmod()` on that fd.
M3. MINOR: `config_test.go`, the other-owner refusal (`checkDirSafe`, `config.go:~192`) has no test. A test does not need root:
    call `checkDirSafe` with a fake `fs.FileInfo` whose `Sys()` returns `&syscall.Stat_t{Uid: uid+1}`.
    Do not use `/usr` or `/tmp` in tests: run as root, they would be chmod'ed.
N1. NIT: `httpapi.go:38-39`. The request is now logged after `next.ServeHTTP`. For the T06 long-lived WS handler, that means
    no log line until the socket closes, and none at all on panic. Note for T06 (log on entry with the pattern from `mux.Handler(r)`).
    The log also relies on ServeMux mutating `r.Pattern` in place. A middleware that clones `r` would silently log only the path.
N2. NIT: `config.go:64`. A config file containing just `null` is accepted and gives the defaults (probe). This existed before T03b and is harmless.
    `checkKeys` could require `{`.
N3. NIT: `gates.sh:51`. When `android/` exists but `gradlew` is missing, the step SKIPs with exit 0. This matches the brief ("SKIP only when
    absent"). Since `android/` is committed, `[[ -d android ]]` → FAIL on a missing gradlew would close the last false green.

## Not run
Live server binary run, the full gates.sh against the real repo (PM gate-2 covers it), shellcheck (not installed).

VERDICT: PASS-WITH-FINDINGS
