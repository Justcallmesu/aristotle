"""Build docs/design/v3/track-record.html from src.html: inlines base.css (copied from v2/screener-src), icons.json,
fonts.css and strings.json (EN + ID), so the board is self-contained. Font sizes in px inside `font:` / `font-size:` are
rewritten to calc(Npx * var(--fs)) so the Tweak "Text 1.3x" scales text like Compose font scale (icons and boxes stay in dp).
Run: python3 docs/design/v3/track-record-src/build.py"""
import json, pathlib, re

here = pathlib.Path(__file__).parent
out = here.parent / "track-record.html"
html = (here / "src.html").read_text()
for marker in ("/*@BASECSS@*/", "/*@ICONS@*/{}", "/*@FONTS@*/", "/*@STRINGS@*/{}"):
    assert marker in html, marker
html = html.replace("/*@BASECSS@*/", (here / "base.css").read_text())


def scale(match):
    return re.sub(r"(\d+(?:\.\d+)?)px", r"calc(\1px*var(--fs))", match.group(0))


html = re.sub(r"font(?:-size)?:[^;}\"']*", scale, html)
icons = json.loads((here / "icons.json").read_text())
html = html.replace("/*@ICONS@*/{}", json.dumps(icons, separators=(",", ":")))
strings = json.loads((here / "strings.json").read_text())
html = html.replace("/*@STRINGS@*/{}", json.dumps(strings, ensure_ascii=False, separators=(",", ":")))
html = html.replace("/*@FONTS@*/", "\n" + (here / "fonts.css").read_text() + "\n")
out.write_text(html)
assert "http://" not in html.replace("http://www.w3.org", "") and "https://" not in html, "external URL"
print(len(icons), "icons;", len(strings), "strings;", len(html) // 1024, "KB ->", out)
