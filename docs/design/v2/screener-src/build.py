"""Build docs/design/v2/screener.html from src.html: inlines base.css (trimmed from stocks-src), icons.json and fonts.css
(both copied from stocks.html, so the board is self-contained). Run: python3 docs/design/v2/screener-src/build.py"""
import json, pathlib

here = pathlib.Path(__file__).parent
out = here.parent / "screener.html"
html = (here / "src.html").read_text()
for marker in ("/*@BASECSS@*/", "/*@ICONS@*/{}", "/*@FONTS@*/"):
    assert marker in html, marker
html = html.replace("/*@BASECSS@*/", (here / "base.css").read_text())
icons = json.loads((here / "icons.json").read_text())
html = html.replace("/*@ICONS@*/{}", json.dumps(icons, separators=(",", ":")))
html = html.replace("/*@FONTS@*/", "\n" + (here / "fonts.css").read_text() + "\n")
out.write_text(html)
print(len(icons), "icons;", len(html) // 1024, "KB ->", out)
