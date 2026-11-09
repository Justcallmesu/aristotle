package httpapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"time"
)

const (
	appAPKName  = "aristotle.apk"
	appMetaName = "aristotle.json"
	apkMIME     = "application/vnd.android.package-archive"
	maxMetaSize = 4 << 10
)

var sha256Hex = regexp.MustCompile(`^[0-9a-f]{64}$`)

// AppUpdate is the body of GET /v1/app/update and the content of aristotle.json (docs/protocol.md §21).
type AppUpdate struct {
	Version     string `json:"version"`
	VersionCode int    `json:"version_code"`
	SHA256      string `json:"sha256"`
	Size        int64  `json:"size"`
}

// readAppUpdate loads the pair install.sh wrote into dir and opens the APK. The caller closes the file.
// Any missing, unreadable or inconsistent pair is an error (the routes answer 204).
func readAppUpdate(dir string) (AppUpdate, *os.File, error) {
	var u AppUpdate
	meta, err := readSmallFile(filepath.Join(dir, appMetaName))
	if err != nil {
		return u, nil, err
	}
	if err := json.Unmarshal(meta, &u); err != nil {
		return AppUpdate{}, nil, errors.New("aristotle.json is not valid JSON")
	}
	if u.Version == "" || u.VersionCode <= 0 || !sha256Hex.MatchString(u.SHA256) || u.Size <= 0 {
		return AppUpdate{}, nil, errors.New("aristotle.json has missing or invalid fields")
	}
	f, err := os.Open(filepath.Join(dir, appAPKName))
	if err != nil {
		return AppUpdate{}, nil, err
	}
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() != u.Size {
		f.Close()
		return AppUpdate{}, nil, fmt.Errorf("aristotle.apk does not match aristotle.json (size)")
	}
	return u, f, nil
}

func readSmallFile(path string) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	buf := make([]byte, maxMetaSize+1)
	n, _ := f.Read(buf)
	if n > maxMetaSize {
		return nil, errors.New("aristotle.json too large")
	}
	return buf[:n], nil
}

// appUpdateHandler answers GET /v1/app/update. The pair is re-read on every request, so install.sh
// needs no server restart. A missing or damaged pair is 204 plus one log line (never the file content).
func appUpdateHandler(dir string, log *slog.Logger) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		u, f, ok := loadApp(dir, log)
		if !ok {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		f.Close()
		writeJSON(w, http.StatusOK, u)
	})
}

// appAPKHandler streams the APK with http.ServeContent: Content-Length, ETag and Range come with it.
func appAPKHandler(dir string, log *slog.Logger) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		u, f, ok := loadApp(dir, log)
		if !ok {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		defer f.Close()
		w.Header().Set("Content-Type", apkMIME)
		w.Header().Set("ETag", `"`+u.SHA256+`"`)
		w.Header().Set("Cache-Control", "no-store")
		http.ServeContent(w, r, appAPKName, time.Time{}, f) // zero time: no Last-Modified, the ETag alone identifies the build
	})
}

func loadApp(dir string, log *slog.Logger) (AppUpdate, *os.File, bool) {
	u, f, err := readAppUpdate(dir)
	if err == nil {
		return u, f, true
	}
	if errors.Is(err, os.ErrNotExist) {
		log.Info("app update: nothing installed, answering 204")
	} else {
		log.Warn("app update unusable, answering 204", "reason", err.Error())
	}
	return AppUpdate{}, nil, false
}
