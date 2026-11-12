#!/usr/bin/env bash
# Root installer: picks a release bundle from dist/ and runs its install.sh.
# Usage: ./install.sh [--bundle <dir> | --bundle=<dir>] [args...]
#   Without --bundle: the newest aristotle-<x.y.z> in dist/ by numeric version. If that bundle's install.sh is missing
#   or not executable it is an error (exit 1): an older bundle is never used instead.
#   Every argument except --bundle is passed unchanged to the bundle's install.sh (--pair, --no-pair, --no-gateway,
#   --no-firewall).
#   --bundle may appear anywhere; given twice or without a value -> usage error (exit 2).
#   -h/--help -> usage (exit 0). No bundle found -> error (exit 1).
#   Refuses to run as root.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
USAGE="usage: ./install.sh [--bundle <dir> | --bundle=<dir>] [args...]
  Other flags go to the bundle's install.sh: --pair, --no-pair, --no-gateway, --no-firewall"

usage() {
  echo "$USAGE" >&2
  exit 2
}

# current_uid is a function (EUID is read-only in bash) so tests can stub `id`.
current_uid() { id -u; }

require_not_root() {
  if [[ "$(current_uid)" == 0 ]]; then
    echo "error: aristotle-server is a per-user systemd service; run this as your user, not as root" >&2
    exit 1
  fi
}

# parse_args: sets BUNDLE_SET, BUNDLE and OTHER_ARGS
parse_args() {
  BUNDLE=""
  BUNDLE_SET=""
  OTHER_ARGS=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h | --help)
        echo "$USAGE"
        exit 0
        ;;
      --bundle)
        if [[ -n $BUNDLE_SET ]]; then
          echo "error: --bundle given more than once" >&2
          usage
        fi
        if [[ $# -lt 2 ]]; then
          echo "error: --bundle requires a directory argument" >&2
          usage
        fi
        BUNDLE_SET=1
        BUNDLE="$2"
        shift 2
        ;;
      --bundle=*)
        if [[ -n $BUNDLE_SET ]]; then
          echo "error: --bundle given more than once" >&2
          usage
        fi
        BUNDLE_SET=1
        BUNDLE="${1#--bundle=}"
        shift
        ;;
      *)
        OTHER_ARGS+=("$1")
        shift
        ;;
    esac
  done
}

# newest_bundle: print the highest aristotle-<x.y.z> dir in dist/ (whatever the state of its install.sh)
newest_bundle() {
  local dir
  # The regex rejects gate-*, -rc1 and other names; sort -V puts 2.10.0 after 2.9.0
  for dir in "$HERE"/dist/aristotle-*/; do
    if [[ $dir =~ /aristotle-[0-9]+\.[0-9]+\.[0-9]+/$ ]]; then
      echo "${dir%/}"
    fi
  done | sort -V | tail -n 1
}

# require_bundle: sets BUNDLE_DIR from --bundle or the newest bundle
require_bundle() {
  if [[ -n $BUNDLE_SET ]]; then
    BUNDLE_DIR="$BUNDLE"
    [[ -d $BUNDLE_DIR ]] || {
      echo "error: bundle directory does not exist: $BUNDLE_DIR" >&2
      exit 1
    }
  else
    BUNDLE_DIR="$(newest_bundle)"
    [[ -n $BUNDLE_DIR ]] || {
      echo "error: no release bundle found in $HERE/dist/" >&2
      echo "  scripts/release.sh needs the signing key (dev PC only): copy a release bundle folder to this PC and pass --bundle <dir>" >&2
      exit 1
    }
  fi

  [[ -x $BUNDLE_DIR/install.sh ]] || {
    echo "error: $BUNDLE_DIR/install.sh is missing or not executable (an incomplete copy?); fix it (chmod +x) or pass --bundle <dir>" >&2
    exit 1
  }
}

require_not_root
parse_args "$@"
require_bundle

echo "Installing $BUNDLE_DIR"
exec "$BUNDLE_DIR/install.sh" "${OTHER_ARGS[@]}"
