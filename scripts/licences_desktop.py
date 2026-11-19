#!/usr/bin/env python3
"""Desktop licence notices (P24), called by scripts/licences.sh. Prints the notices text on stdout.

Inventory, all from lock files, nothing guessed:
  * Rust: the crates linked into aristotle-tray and aristotle-desktop (`cargo tree -e normal --target
    x86_64-unknown-linux-gnu`, `cargo metadata` for the manifests): name, version, the licence expression from Cargo.toml,
    and every LICENSE/LICENCE/COPYING/UNLICENSE file in the crate's registry source, verbatim. The workspace's own crates
    are ours. Plus the Rust standard library and runtime (MIT OR Apache-2.0), linked into both binaries.
  * npm: desktop/ui/package-lock.json entries that are not dev-only (the `dependencies` closure), with their LICENSE file
    from desktop/ui/node_modules (`npm ci` is run there when the directory is missing, so a fresh clone works).
  * the bundled fonts' OFL texts from desktop/ui/src/assets/fonts/.
A package that ships no licence file gets the standard text of the licence its manifest declares (scripts/licence-texts/<SPDX
id>.txt, every alternative of an `A OR B`), marked "standard text of the declared licence; the crate ships none", with a
copyright line from the manifest `authors` (or "the <name> authors"). A package with no declared licence, an identifier
without a standard text here, or a `WITH` exception, is an error: stderr names it and the exit status is 1. Nothing is guessed.
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DESKTOP = os.path.join(ROOT, "desktop")
RULE = "=" * 64
TARGET = "x86_64-unknown-linux-gnu"
TEXTS = os.path.join(ROOT, "scripts", "licence-texts")
MARK = "standard text of the declared licence; the crate ships none"
OPERATORS = ("AND", "OR", "WITH")
PLACEHOLDER = re.compile(r"^Copyright \(c\) <year>.*$")
BINARIES = ("aristotle-tray", "aristotle-desktop")
LICENCE_PREFIXES = ("license", "licence", "copying", "unlicense")
FONT_FILES = (
    ("Atkinson Hyperlegible Next and Atkinson Hyperlegible Mono (bundled fonts)", "OFL.txt"),
    ("Literata (bundled font)", "OFL-Literata.txt"),
)


def licence_files(directory):
    """LICENSE-like files directly in `directory`, sorted by name (a file, not a directory, named like a licence)."""
    if not os.path.isdir(directory):
        return []
    names = sorted(
        n for n in os.listdir(directory)
        if n.lower().startswith(LICENCE_PREFIXES) and os.path.isfile(os.path.join(directory, n))
    )
    return [os.path.join(directory, n) for n in names]


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().rstrip("\n")


def spdx_ids(expression):
    """The licence identifiers of an SPDX expression (`A OR B`, `A AND B`, parentheses, the old `A/B`), in order, unique."""
    ids = []
    for token in re.findall(r"[A-Za-z0-9.+\-]+", expression):
        if token not in OPERATORS and token not in ids:
            ids.append(token)
    return ids


def standard_text(licence_id):
    """The vendored standard text of `licence_id` with the placeholder copyright line removed, or None."""
    path = os.path.join(TEXTS, licence_id + ".txt")
    if not os.path.isfile(path):
        return None
    lines = [line for line in read(path).split("\n") if not PLACEHOLDER.match(line)]
    return "\n".join(lines).strip("\n")


def declared_texts(title, name, expression, authors, errors):
    """Output lines for a package that ships no licence file: the standard text of every declared licence."""
    if not expression:
        errors.append("%s: no licence file in its source and no licence declared in its manifest" % title)
        return ["(The package ships no licence file and declares no licence.)", ""]
    if "WITH" in re.findall(r"[A-Za-z0-9.+\-]+", expression):
        errors.append("%s: declared '%s' has a WITH exception; no standard text for it" % (title, expression))
        return ["(The package ships no licence file; its manifest declares: %s.)" % expression, ""]
    holder = ", ".join(authors) if authors else "the %s authors" % name
    out = ["Copyright (c) %s" % holder, ""]
    for licence_id in spdx_ids(expression):
        text = standard_text(licence_id)
        if text is None:
            errors.append("%s: declared licence '%s' has no standard text in scripts/licence-texts" % (title, licence_id))
            continue
        out += ["--- %s (%s)" % (licence_id, MARK), text, ""]
    return out


def section(title, name, expression, files, authors, errors):
    out = [RULE, "%s - %s" % (title, expression or "UNKNOWN LICENCE"), RULE, ""]
    if not files:
        out += declared_texts(title, name, expression, authors, errors)
    for path in files:
        if len(files) > 1:
            out.append("--- %s" % os.path.basename(path))
        out.append(read(path))
        out.append("")
    out.append("")
    return "\n".join(out)


def run_tool(args, cwd, fetch):
    """Run a cargo/npm command with the output captured; `fetch` (a callable) runs once and the command again when it fails."""
    done = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
    if done.returncode != 0 and fetch is not None:
        fetch()
        done = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
    if done.returncode != 0:
        sys.exit("licences: `%s` failed:\n%s" % (" ".join(args), done.stderr.strip()))
    return done.stdout


def cargo_fetch():
    print("licences: crate sources missing; running `cargo fetch --locked`", file=sys.stderr)
    subprocess.run(["cargo", "fetch", "--locked"], cwd=DESKTOP, check=True, stdout=sys.stderr)


def cargo_metadata():
    out = run_tool(["cargo", "metadata", "--format-version", "1", "--locked", "--offline", "--filter-platform", TARGET], DESKTOP, cargo_fetch)
    return json.loads(out)


def linked_set():
    """(name, version) of every package `cargo tree -e normal` lists for the two binaries on the host target."""
    args = ["cargo", "tree", "--locked", "--offline", "-e", "normal", "--target", TARGET, "--prefix", "none", "--format", "{p}"]
    for binary in BINARIES:
        args += ["-p", binary]
    found = set()
    for line in run_tool(args, DESKTOP, cargo_fetch).splitlines():
        m = re.match(r"^(\S+) v(\S+)", line)
        if m:
            found.add((m.group(1), m.group(2)))
    return found


def linked_crates(meta):
    """Packages in the `cargo tree` set of the two binaries, minus the workspace's own, sorted."""
    linked = linked_set()
    members = set(meta["workspace_members"])
    packages = [p for p in meta["packages"] if p["id"] not in members and (p["name"], p["version"]) in linked]
    missing = linked - {(p["name"], p["version"]) for p in meta["packages"]}
    if missing:
        sys.exit("licences: in `cargo tree` but not in `cargo metadata`: %s" % sorted(missing))
    return sorted(packages, key=lambda p: (p["name"], p["version"]))


def rust_version():
    out = subprocess.run(["rustc", "-V"], check=True, capture_output=True, text=True).stdout.split()
    return out[1]


def rust_std_notice():
    """The Rust standard library and runtime is statically linked into both binaries: MIT OR Apache-2.0, texts vendored."""
    title = "Rust standard library and runtime (rustc %s, the toolchain that builds the desktop programs)" % rust_version()
    out = [RULE, "%s - MIT OR Apache-2.0" % title, RULE, "", "Copyright (c) The Rust Project Contributors", ""]
    for licence_id in ("MIT", "Apache-2.0"):
        out += ["--- %s" % licence_id, standard_text(licence_id), ""]
    out.append("")
    return "\n".join(out)


def rust_notices(errors):
    parts = [rust_std_notice()]
    for p in linked_crates(cargo_metadata()):
        directory = os.path.dirname(p["manifest_path"])
        title = "%s %s (Rust crate)" % (p["name"], p["version"])
        parts.append(section(title, p["name"], p.get("license"), licence_files(directory), p.get("authors") or [], errors))
    return "".join(parts)


def npm_notices(errors):
    ui = os.path.join(DESKTOP, "ui")
    if not os.path.isdir(os.path.join(ui, "node_modules")):
        print("licences: desktop/ui/node_modules missing; running `npm ci`", file=sys.stderr)
        subprocess.run(["npm", "ci"], cwd=ui, check=True, stdout=sys.stderr)
    with open(os.path.join(ui, "package-lock.json"), encoding="utf-8") as f:
        lock = json.load(f)
    parts = []
    for key, entry in sorted(lock["packages"].items()):
        if not key or entry.get("dev") or entry.get("devOptional") and not entry.get("optional"):
            continue
        directory = os.path.join(ui, key)
        if not os.path.isdir(directory):
            sys.exit("licences: %s is missing even after `npm ci` in desktop/ui" % directory)
        name = key.split("node_modules/")[-1]
        title = "%s %s (npm package)" % (name, entry["version"])
        parts.append(section(title, name, entry.get("license"), licence_files(directory), [], errors))
    return "".join(parts)


def font_notices():
    parts = []
    for title, name in FONT_FILES:
        path = os.path.join(DESKTOP, "ui", "src", "assets", "fonts", name)
        parts.append("%s\n%s - OFL-1.1\n%s\n\n%s\n\n\n" % (RULE, title, RULE, read(path)))
    return "".join(parts)


def main():
    errors = []
    head = (
        "Third-party notices for aristotle-tray and aristotle-desktop\n"
        "Generated by scripts/licences.sh (scripts/licences_desktop.py) from desktop/Cargo.lock (normal dependencies of the two\n"
        "binaries), desktop/ui/package-lock.json (runtime dependencies) and desktop/ui/src/assets/fonts. Do not edit by hand.\n\n"
    )
    body = rust_notices(errors) + npm_notices(errors) + font_notices()
    if errors:
        for e in errors:
            print("licences: " + e, file=sys.stderr)
        sys.exit(1)
    sys.stdout.write(head + body)


main()
