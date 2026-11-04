"""Inline icon paths and fonts into docs/design/v2/stocks.html (replaces the /*@ICONS@*/{} and /*@FONTS@*/ markers)."""
import base64, json, pathlib, re

here = pathlib.Path(__file__).parent
html_p = pathlib.Path("/home/justcallmesu/project/personal/Aristotle/docs/design/v2/stocks.html")
src_p = here / "src.html"
html = src_p.read_text()

icons = {}
for svg in sorted((here / "ic").glob("*.svg")):
    d = re.search(r'<path d="([^"]+)"', svg.read_text()).group(1)
    icons[svg.stem] = d

def face(family, file, weight, fmt="woff2"):
    b64 = base64.b64encode((here / file).read_bytes()).decode()
    return f'@font-face{{font-family:"{family}";font-style:normal;font-weight:{weight};font-display:block;src:url(data:font/{fmt};base64,{b64}) format("{fmt}")}}'

fonts = "\n".join([
    face("Atkinson Hyperlegible Next", "next_regular.woff2", 400),
    face("Atkinson Hyperlegible Next", "next_medium.woff2", 500),
    face("Atkinson Hyperlegible Next", "next_semibold.woff2", 600),
    face("Atkinson Hyperlegible Mono", "mono_regular.woff2", 400),
    face("Atkinson Hyperlegible Mono", "mono_medium.woff2", 500),
    face("Atkinson Hyperlegible Mono", "mono_semibold.woff2", 600),
    face("Literata", "literata.woff2", "200 900"),
])
assert "/*@ICONS@*/{}" in html and "/*@FONTS@*/" in html
html = html.replace("/*@ICONS@*/{}", json.dumps(icons, separators=(",", ":")))
html = html.replace("/*@FONTS@*/", "\n" + fonts + "\n")
html_p.write_text(html)
print(len(icons), "icons;", len(html) // 1024, "KB")
