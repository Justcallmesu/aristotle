package hermes

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Hermes-home marker files: a directory holding one of them is a real Hermes home, so its profiles/
// child is a profiles root (hermes_constants.py:319-320 _HERMES_HOME_MARKERS).
var hermesHomeMarkers = []string{"config.yaml", ".env", "state.db"}

// multiplexerRootFor returns the root whose gateway may serve home when home is a named profile home
// <root>/profiles/<name>, else "". Mirrors hermes_cli/gateway_runtime_multiplex.py:18-24
// (multiplexer_root_for) with hermes_constants.py:323-357 (_is_hermes_profiles_root, named_profile_home):
// the name must not start with "." and <root> must provably be a Hermes home. Hermes also accepts
// <root> == get_default_hermes_root(); that root is ~/.hermes, which the ".hermes" name rule already covers
// unless HERMES_DATA_DIR_SUFFIX is set. Like Hermes (_canonical_home), the home is resolved first, so a
// HERMES_HOME that is a symlink to a profile home finds that profile's root.
func multiplexerRootFor(home string) string {
	home = canonicalHome(home)
	profiles := filepath.Dir(home)
	if filepath.Base(profiles) != "profiles" || strings.HasPrefix(filepath.Base(home), ".") {
		return ""
	}
	root := filepath.Dir(profiles)
	if root == home || !isProfilesRoot(root, profiles) {
		return ""
	}
	return root
}

func isProfilesRoot(root, profiles string) bool {
	if filepath.Base(root) == ".hermes" {
		return true
	}
	if info, err := os.Stat(filepath.Join(profiles, ".deleted")); err == nil && info.IsDir() {
		return true
	}
	for _, marker := range hermesHomeMarkers {
		// Stat, not Lstat: Hermes uses Path.exists(), which follows links (a dangling link is no marker).
		if _, err := os.Stat(filepath.Join(root, marker)); err == nil {
			return true
		}
	}
	return false
}

// identifyServedProfile is Hermes' own fallback for a named profile without a gateway of its own
// (hermes_cli/gateway_runtime.py:289-305 discover_gateway_endpoint -> :209-236 _served_by_multiplexer):
// ask the root's control socket, and accept it only when its served_profiles lists this exact home
// (_endpoint, :98-113, profile_mismatch otherwise). The root's socket then mints this profile's tickets
// (control_home_for, :155-159). A root that runs but does not serve the profile is "not running" for it.
func (c *Connector) identifyServedProfile(ctx context.Context, ownErr error) (Identity, error) {
	id, err := c.identifyAt(ctx, c.root)
	if errors.Is(err, ErrHermesNotRunning) {
		return Identity{}, ownErr
	}
	if err != nil {
		return Identity{}, err
	}
	if !id.serves(c.home) {
		return Identity{}, fmt.Errorf("%w: the gateway of %s does not serve profile home %s", ErrHermesNotRunning, c.root, c.home)
	}
	id.profileHome = c.home
	return id, nil
}

// serves reports whether exactly one served profile has home as its home and that one has an id, as
// Hermes' _endpoint counts every entry for the home before it checks the id (gateway_runtime.py:106-110).
func (id Identity) serves(home string) bool {
	var match *ServedProfile
	for i, p := range id.ServedProfiles {
		if !sameHome(p.Home, home) {
			continue
		}
		if match != nil {
			return false
		}
		match = &id.ServedProfiles[i]
	}
	return match != nil && match.ProfileID != ""
}

// sameHome compares two home paths the way Hermes does (Path.resolve on both sides,
// hermes_cli/gateway_runtime.py:65-66): symlinks resolved when the path exists, else cleaned.
func sameHome(a, b string) bool {
	return canonicalHome(a) == canonicalHome(b)
}

func canonicalHome(path string) string {
	if path == "" {
		return ""
	}
	if abs, err := filepath.Abs(path); err == nil {
		path = abs
	}
	if resolved, err := filepath.EvalSymlinks(path); err == nil {
		return resolved
	}
	return filepath.Clean(path)
}
