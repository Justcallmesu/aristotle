package httpapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

var testAPK = []byte("PK fake apk bytes 0123456789")

// appRemote pairs a device against a router whose app dir is dir.
func appRemote(t *testing.T, dir string) (*remote, string) {
	t.Helper()
	rm := newRemote(t)
	rm.h = NewRemoteRouter("t", slog.New(slog.NewTextHandler(rm.logs, nil)), Deps{Codes: rm.codes, Devices: rm.store, AppDir: dir})
	return rm, rm.pair(t, "", "").Token
}

func writeApp(t *testing.T, dir string, apk []byte, meta string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, "aristotle.apk"), apk, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "aristotle.json"), []byte(meta), 0o644); err != nil {
		t.Fatal(err)
	}
}

func goodMeta(apk []byte) string {
	sum := sha256.Sum256(apk)
	return fmt.Sprintf(`{"version":"2.1.0","version_code":20100,"sha256":"%s","size":%d}`, hex.EncodeToString(sum[:]), len(apk))
}

func TestAppUpdateNeedsToken(t *testing.T) {
	dir := t.TempDir()
	writeApp(t, dir, testAPK, goodMeta(testAPK))
	rm, _ := appRemote(t, dir)
	for _, path := range []string{"/v1/app/update", "/v1/app/apk"} {
		if rec := rm.do(http.MethodGet, path, "", ""); rec.Code != http.StatusUnauthorized {
			t.Errorf("%s without token = %d", path, rec.Code)
		}
	}
}

func TestAppUpdateJSONAndAPK(t *testing.T) {
	dir := t.TempDir()
	writeApp(t, dir, testAPK, goodMeta(testAPK))
	rm, tok := appRemote(t, dir)

	rec := rm.do(http.MethodGet, "/v1/app/update", "", tok)
	if rec.Code != http.StatusOK || rec.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("update = %d %q", rec.Code, rec.Header().Get("Cache-Control"))
	}
	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(testAPK)
	want := map[string]any{"version": "2.1.0", "version_code": 20100.0, "sha256": hex.EncodeToString(sum[:]), "size": float64(len(testAPK))}
	if len(got) != 4 {
		t.Errorf("keys = %v", got)
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("%s = %v, want %v", k, got[k], v)
		}
	}

	rec = rm.do(http.MethodGet, "/v1/app/apk", "", tok)
	if rec.Code != http.StatusOK || rec.Body.String() != string(testAPK) {
		t.Fatalf("apk = %d %q", rec.Code, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/vnd.android.package-archive" {
		t.Errorf("content-type = %q", ct)
	}
	if cl := rec.Header().Get("Content-Length"); cl != fmt.Sprint(len(testAPK)) {
		t.Errorf("content-length = %q", cl)
	}
	if et := rec.Header().Get("ETag"); et != `"`+hex.EncodeToString(sum[:])+`"` {
		t.Errorf("etag = %q", et)
	}
	if rm.do(http.MethodPost, "/v1/app/apk", "", tok).Code != http.StatusNotFound {
		t.Error("POST /v1/app/apk accepted")
	}
}

func TestAppAPKRange(t *testing.T) {
	dir := t.TempDir()
	writeApp(t, dir, testAPK, goodMeta(testAPK))
	rm, tok := appRemote(t, dir)
	req := func(rng string) int {
		return rm.doWith(http.MethodGet, "/v1/app/apk", tok, map[string]string{"Range": rng}).Code
	}
	if c := req("bytes=3-"); c != http.StatusPartialContent {
		t.Errorf("range = %d, want 206", c)
	}
}

func TestAppUpdateNone(t *testing.T) {
	cases := map[string]func(dir string){
		"empty dir": func(string) {},
		"json only": func(dir string) {
			_ = os.WriteFile(filepath.Join(dir, "aristotle.json"), []byte(goodMeta(testAPK)), 0o644)
		},
		"corrupt json": func(dir string) {
			writeApp(t, dir, testAPK, "{not json")
		},
		"size mismatch": func(dir string) {
			writeApp(t, dir, append([]byte("x"), testAPK...), goodMeta(testAPK))
		},
		"bad sha": func(dir string) {
			writeApp(t, dir, testAPK, fmt.Sprintf(`{"version":"2.1.0","version_code":20100,"sha256":"zz","size":%d}`, len(testAPK)))
		},
		"no version": func(dir string) {
			writeApp(t, dir, testAPK, strings.Replace(goodMeta(testAPK), `"version":"2.1.0"`, `"version":""`, 1))
		},
	}
	for name, setup := range cases {
		t.Run(name, func(t *testing.T) {
			dir := t.TempDir()
			setup(dir)
			rm, tok := appRemote(t, dir)
			for _, path := range []string{"/v1/app/update", "/v1/app/apk"} {
				if rec := rm.do(http.MethodGet, path, "", tok); rec.Code != http.StatusNoContent || rec.Body.Len() != 0 {
					t.Errorf("%s = %d %q, want 204 empty", path, rec.Code, rec.Body.String())
				}
			}
		})
	}
}

func TestAppUpdateLogsOneLinePerDamagedRequest(t *testing.T) {
	dir := t.TempDir()
	writeApp(t, dir, testAPK, "{not json")
	rm, tok := appRemote(t, dir)
	rm.do(http.MethodGet, "/v1/app/update", "", tok)
	if n := strings.Count(rm.logs.String(), "app update"); n != 1 {
		t.Errorf("log lines mentioning app update = %d:\n%s", n, rm.logs.String())
	}
}

func TestAppUpdateRereadsEachRequest(t *testing.T) {
	dir := t.TempDir()
	rm, tok := appRemote(t, dir)
	if rm.do(http.MethodGet, "/v1/app/update", "", tok).Code != http.StatusNoContent {
		t.Fatal("want 204 before install")
	}
	writeApp(t, dir, testAPK, goodMeta(testAPK))
	if rm.do(http.MethodGet, "/v1/app/update", "", tok).Code != http.StatusOK {
		t.Error("want 200 after install without restart")
	}
}

func TestAppRoutesAbsentWithoutDir(t *testing.T) {
	rm := newRemote(t)
	tok := rm.pair(t, "", "").Token
	if rec := rm.do(http.MethodGet, "/v1/app/update", "", tok); rec.Code != http.StatusNotFound {
		t.Errorf("no AppDir = %d", rec.Code)
	}
}
