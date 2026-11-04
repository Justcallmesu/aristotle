#!/usr/bin/env bash
# TR3 isolated gate: HEAD + only TR3's files, in a scratch copy (other workers' half-done edits excluded).
# ChatScreen's composer wiring (modelChip / onOpenModels / onOpenPrompt) needs TR4's Composer params, which HEAD lacks:
# those three lines are dropped in the copy only.
set -euo pipefail
REPO=/home/justcallmesu/project/personal/Aristotle
DST="$TMPDIR/tr3-gate"
rm -rf "$DST"; mkdir -p "$DST"
cd "$REPO"
git archive HEAD | tar -x -C "$DST"
cp android/local.properties "$DST/android/" 2>/dev/null || true
C=android/app/src/main/java/io/github/justcallmesu/aristotle/ui/chat
T=android/app/src/test/java/io/github/justcallmesu/aristotle/ui/chat
for f in $C/ToolFolding.kt $C/ChatItems.kt $C/ChatScreen.kt $C/ChatText.kt $C/ChatMarkdown.kt $C/SubagentViews.kt \
         $T/ToolFoldingTest.kt $T/ToolFoldingRealTurnsTest.kt $T/ChatSubtitleTest.kt \
         android/app/src/main/res/drawable/ic_psychology.xml android/app/src/main/res/drawable/ic_account_tree.xml; do
  cp "$f" "$DST/$f"
done
# Strings: HEAD + only TR3's appended block.
python3 - "$REPO" "$DST" <<'EOF'
import sys
repo, dst = sys.argv[1], sys.argv[2]
for v in ("values", "values-in"):
    p = f"android/app/src/main/res/{v}/strings.xml"
    cur = open(f"{repo}/{p}", encoding="utf-8").read()
    a = cur.index("    <!-- TR3: chat transcript v2")
    b = cur.index('<string name="chat_needs_approval">', a)
    b = cur.index("\n", b) + 1
    block = cur[a:b]
    head = open(f"{dst}/{p}", encoding="utf-8").read()
    i = head.rindex("</resources>")
    open(f"{dst}/{p}", "w", encoding="utf-8").write(head[:i] + block + head[i:])
s = open(f"{dst}/android/app/src/main/java/io/github/justcallmesu/aristotle/ui/chat/ChatScreen.kt").read()
for line in ["                    // design-notes §5.2: the model and effort moved from the top bar's subtitle into the composer's chip.\n",
             "                    onOpenModels = models::open,\n", "                    onOpenPrompt = onOpenSheet,\n"]:
    assert line in s, line
    s = s.replace(line, "")
import re
s, n = re.subn(r"                    modelChip = ComposerLogic\.chipLabel\(.*\n", "", s)
assert n == 1
open(f"{dst}/android/app/src/main/java/io/github/justcallmesu/aristotle/ui/chat/ChatScreen.kt", "w").write(s)
print("copy ready")
EOF
cd "$DST/android"
code=0
./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug > "$DST/gate.log" 2>&1 || code=$?
echo "TR3_ISOLATED_GATE_EXIT=$code"
grep -E "FAILED|Lint found|tests completed|BUILD" "$DST/gate.log" || true
