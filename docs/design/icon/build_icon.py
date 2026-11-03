#!/usr/bin/env python3
"""T20: the Aristotle app icon (concept B, simplified) from one set of paths.

Writes the master SVGs, the Android vector drawables and adaptive-icon XML, the preview sheet and its PNG renders.
Run from anywhere: python3 docs/design/icon/build_icon.py  (needs rsvg-convert for the PNGs).
Edit the geometry here, never the generated files.
"""
import math
import os
import re
import shutil
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
PNG = os.path.join(HERE, "png")

INK, PAPER = "#2B2119", "#F3EAD8"
STROKE = 4.8  # canvas units; the 72-unit visible area shows at 48 dp, so 4.8 units = 3.2 dp

# Geometry on the 108x108 adaptive canvas. Everything stays inside the 66 dp safe zone (circle r33 at 54,54).
# Fills use the even-odd rule, so the drapery fold is a real hole and the monochrome layer needs no mask.
HEAD = ("M63,30.5 C62,26 59,24 55,24 C52,22 47,22 44,24.5 C40,24.5 36,27 35.5,31 C33,33.5 33,37.5 34.5,40 "
        "C33.5,43.5 35.5,47.5 39,49 C41,51.5 44,52 47,52 C47.5,55 49,58 52,60 C56,62.5 61,63.5 66,63 "
        "C69,61.5 70.5,58.5 69.5,56 C71,54 70.5,51 68.5,49 C65,49 61,47 58,44.5 C56.5,42 56,40 56,37.5 "
        "C58,36 61,34 63,32.5 Z")  # curls and beard, one mass framing the face
PROFILE = "M62.5,30.5 C66,32.5 67.5,35.5 67.5,38.5 L71.5,44.5 L68,46.5"  # forehead, brow, nose (stroke)
FIST = ("M51,63.5 C55,66 61,67 66,66.5 C70,66 74,65.5 75,68 C75.8,70.5 74.5,71.8 73,72 C75.3,72.8 75.6,75.3 73.8,76.3 "
        "C75,78 73.8,80.5 70.5,80.5 C68,80.8 66,80.5 64.5,80 L58,86 C55,86.8 51.5,86 49,84.5 L52,74 "
        "C50,71 49.5,67 51,63.5 Z")  # fist under the chin, knuckles forward, forearm down to the bottom
SHOULDER = ("M37,52.8 C40,54.6 43,55.6 45.6,56.2 C46.6,60 46.9,66 46.9,72 L45.9,84.1 C40.8,82.8 36,80 32,76.2 "
            "C28.1,72.3 25.3,67.4 23.9,62.1 C22.8,57.5 29,52.4 37,52.8 Z")  # hunched, draped shoulder
FOLD = "M30.5,62.5 C36.5,65.5 40.5,72 41.8,80 C39.5,80.6 39,79 38.6,78 C36.8,72 34,67 30.5,62.5 Z"  # toga fold (hole)

LAUNCHER = [("fill", HEAD), ("stroke", PROFILE), ("fill", FIST), ("fill", SHOULDER + " " + FOLD)]
STATUS = [("fill", HEAD), ("stroke", PROFILE), ("fill", FIST), ("fill", SHOULDER)]  # fold too thin at 24 dp

MASKS = {
    "circle": '<circle cx="54" cy="54" r="36"/>',
    "squircle": '<path d="M54,18 C80,18 90,28 90,54 C90,80 80,90 54,90 C28,90 18,80 18,54 C18,28 28,18 54,18 Z"/>',
    "rounded square": '<rect x="18" y="18" width="72" height="72" rx="15"/>',
}
# Backdrops and Android 13 themed-icon tones (the launcher picks these; the values are representative).
SCENES = [
    ("light", "Light wallpaper", "#E6E1D6", "#3A3530", None),
    ("dark", "Dark wallpaper", "#121417", "#E6E1D6", None),
    ("themed-light", "Themed icon, light", "#F4F5FA", "#3A3530", ("#D7E3F7", "#101C2B")),
    ("themed-dark", "Themed icon, dark", "#0F1216", "#E6E1D6", ("#1F2A36", "#BBC7DB")),
]
SIZES = (48, 64, 108)


# ---------- geometry checks ----------

def sample(d, n=32):
    toks = re.findall(r"[MLCZ]|-?[\d.]+", d)
    pts, i, cmd = [], 0, None
    cur = start = (0.0, 0.0)
    while i < len(toks):
        if toks[i] in "MLCZ":
            cmd = toks[i]
            i += 1
            if cmd == "Z":
                cur = start
                continue
        vals = [float(v) for v in toks[i:i + (6 if cmd == "C" else 2)]]
        i += len(vals)
        if cmd == "M":
            cur = start = (vals[0], vals[1])
            pts.append(cur)
            cmd = "L"
        elif cmd == "L":
            cur = (vals[0], vals[1])
            pts.append(cur)
        elif cmd == "C":
            p0, p1, p2, p3 = cur, vals[0:2], vals[2:4], vals[4:6]
            for k in range(1, n + 1):
                t = k / n
                a, b, c, e = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3
                pts.append((a * p0[0] + b * p1[0] + c * p2[0] + e * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + e * p3[1]))
            cur = (p3[0], p3[1])
    return pts


def extent(elems, pad_stroke=True):
    """(max distance from the canvas centre, bbox) including half the stroke width."""
    worst, xs, ys = 0.0, [], []
    for kind, d in elems:
        pad = STROKE / 2 if (kind == "stroke" and pad_stroke) else 0
        for x, y in sample(d):
            worst = max(worst, math.hypot(x - 54, y - 54) + pad)
            xs += [x - pad, x + pad]
            ys += [y - pad, y + pad]
    return worst, (min(xs), min(ys), max(xs), max(ys))


# ---------- SVG ----------

def glyph(elems, color):
    out = []
    for kind, d in elems:
        if kind == "fill":
            out.append(f'<path d="{d}" fill="{color}" fill-rule="evenodd"/>')
        else:
            out.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{STROKE}" '
                       f'stroke-linecap="round" stroke-linejoin="round"/>')
    return "".join(out)


_uid = [0]


def tile(mask, px, scene_fg_bg=None):
    """One launcher tile: the 72-unit visible area clipped to a mask, drawn at px x px."""
    _uid[0] += 1
    cid = f"m{_uid[0]}"
    if scene_fg_bg:  # themed: launcher tone behind, monochrome glyph tinted
        bg, fg = scene_fg_bg
    else:
        bg, fg = PAPER, INK
    return (f'<svg width="{px}" height="{px}" viewBox="18 18 72 72" role="img" aria-label="{px} dp">'
            f'<defs><clipPath id="{cid}">{MASKS[mask]}</clipPath></defs><g clip-path="url(#{cid})">'
            f'<rect width="108" height="108" fill="{bg}"/>{glyph(LAUNCHER, fg)}</g></svg>')


def master_svgs():
    r, _ = extent(LAUNCHER)
    note = f"Max extent {r:.1f} of 33 (safe zone). Generated by build_icon.py."
    color = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">\n'
             f'<!-- T20 Aristotle icon, concept B simplified: 108x108 dp adaptive canvas, ink on parchment. {note} -->\n'
             f'<rect width="108" height="108" fill="{PAPER}"/>\n{glyph(LAUNCHER, INK)}\n</svg>\n')
    mono = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">\n'
            f'<!-- T20 monochrome layer: one colour, transparent background, fold is a real hole. {note} -->\n'
            f'{glyph(LAUNCHER, "#000000")}\n</svg>\n')
    write(os.path.join(HERE, "aristotle-b2.svg"), color)
    write(os.path.join(HERE, "aristotle-b2-monochrome.svg"), mono)


# ---------- Android ----------

GEN = "Generated by docs/design/icon/build_icon.py (T20); edit the geometry there."


def vector_paths(elems, color, indent="    "):
    out = []
    for kind, d in elems:
        if kind == "fill":
            out.append(f'{indent}<path\n{indent}    android:fillColor="{color}"\n{indent}    android:fillType="evenOdd"\n'
                       f'{indent}    android:pathData="{d}" />')
        else:
            out.append(f'{indent}<path\n{indent}    android:strokeColor="{color}"\n{indent}    android:strokeWidth="{STROKE}"\n'
                       f'{indent}    android:strokeLineCap="round"\n{indent}    android:strokeLineJoin="round"\n'
                       f'{indent}    android:pathData="{d}" />')
    return "\n".join(out)


def vector(comment, body, size=108, viewport=108):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- {comment} {GEN} -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size}dp"\n    android:height="{size}dp"\n'
            f'    android:viewportWidth="{viewport}"\n    android:viewportHeight="{viewport}">\n'
            f'{body}\n</vector>\n')


def status_body():
    # Fit the mark into a 20 dp square centred in the 24 dp status-bar icon.
    _, (x0, y0, x1, y1) = extent(STATUS)
    s = 20 / max(x1 - x0, y1 - y0)
    tx, ty = 12 - s * (x0 + x1) / 2, 12 - s * (y0 + y1) / 2
    return (f'    <group\n        android:scaleX="{s:.4f}"\n        android:scaleY="{s:.4f}"\n'
            f'        android:translateX="{tx:.3f}"\n        android:translateY="{ty:.3f}">\n'
            f'{vector_paths(STATUS, "#FFFFFFFF", indent="        ")}\n    </group>'), (s, tx, ty)


def android_res():
    d = os.path.join(RES, "drawable")
    write(os.path.join(d, "ic_launcher_foreground.xml"),
          vector("Adaptive icon foreground: ink line art on transparent, inside the 66 dp safe zone.",
                 vector_paths(LAUNCHER, "#FF2B2119")))
    write(os.path.join(d, "ic_launcher_background.xml"),
          vector("Adaptive icon background: flat parchment.",
                 '    <path\n        android:fillColor="#FFF3EAD8"\n        android:pathData="M0,0h108v108h-108z" />'))
    write(os.path.join(d, "ic_launcher_monochrome.xml"),
          vector("Themed icon (Android 13+) layer: one colour, the launcher tints it. Also tinted on the quote screen.",
                 vector_paths(LAUNCHER, "#FFFFFFFF")))
    body, _ = status_body()
    write(os.path.join(d, "ic_stat_aristotle.xml"),
          vector("Status-bar icon: the same mark, white with alpha only.", body, size=24, viewport=24))
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<!-- minSdk 29: adaptive icons only, no legacy PNG mipmaps needed. -->\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
                '</adaptive-icon>\n')
    for name in ("ic_launcher", "ic_launcher_round"):
        write(os.path.join(RES, "mipmap-anydpi", name + ".xml"), adaptive)


# ---------- preview ----------

def canvas_key(px=216):
    return (f'<svg width="{px}" height="{px}" viewBox="0 0 108 108" role="img" aria-label="108 dp canvas">'
            f'<rect width="108" height="108" fill="{PAPER}"/>{glyph(LAUNCHER, INK)}'
            '<circle cx="54" cy="54" r="36" fill="none" stroke="#B3261E" stroke-width="0.5"/>'
            '<circle cx="54" cy="54" r="33" fill="none" stroke="#B3261E" stroke-width="0.5" stroke-dasharray="1.5 1.5"/>'
            '</svg>')


def status_svg(px, color):
    body, (s, tx, ty) = status_body()
    return (f'<svg width="{px}" height="{px}" viewBox="0 0 24 24" role="img" aria-label="status-bar icon">'
            f'<g transform="translate({tx:.3f},{ty:.3f}) scale({s:.4f})">{glyph(STATUS, color)}</g></svg>')


def scene_row(scene):
    key, label, wall, text, themed = scene
    cells = []
    for mask in MASKS:
        figs = "".join(f'<figure>{tile(mask, px, themed)}<figcaption>{px} dp</figcaption></figure>' for px in SIZES)
        cells.append(f'<div class="cell"><span class="mask">{mask}</span><div class="sizes">{figs}</div></div>')
    return (f'<section class="scene" style="background:{wall};color:{text}"><h2>{label}</h2>'
            f'<div class="cells">{"".join(cells)}</div></section>')


def scene_svg(scene, scale=3):
    """A standalone SVG of one scene row for rsvg-convert (same layout as the HTML row)."""
    key, label, wall, text, themed = scene
    gap, pad, head = 24, 24, 40
    widths = [sum(SIZES) + gap * (len(SIZES) - 1) for _ in MASKS]
    w = pad * 2 + sum(widths) + 48 * (len(MASKS) - 1)
    h = head + 24 + max(SIZES) + 28 + pad
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w * scale}" height="{h * scale}" viewBox="0 0 {w} {h}">',
             f'<rect width="{w}" height="{h}" fill="{wall}"/>',
             f'<text x="{pad}" y="{pad + 8}" font-family="sans-serif" font-size="15" font-weight="600" fill="{text}">{label}</text>']
    x = pad
    for mask, cw in zip(MASKS, widths):
        parts.append(f'<text x="{x}" y="{head + 14}" font-family="sans-serif" font-size="12" fill="{text}" opacity=".75">{mask}</text>')
        cx = x
        for px in SIZES:
            y = head + 24 + (max(SIZES) - px)
            parts.append(f'<g transform="translate({cx},{y})">{tile(mask, px, themed)}</g>')
            parts.append(f'<text x="{cx + px / 2}" y="{head + 24 + max(SIZES) + 18}" text-anchor="middle" '
                         f'font-family="sans-serif" font-size="11" fill="{text}" opacity=".75">{px} dp</text>')
            cx += px + gap
        x += cw + 48
    parts.append("</svg>")
    return "".join(parts)


def preview_html(extent_r):
    rows = "".join(scene_row(s) for s in SCENES)
    pngs = "".join(f'<li><a href="png/{s[0]}.png">png/{s[0]}.png</a></li>' for s in SCENES)
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Aristotle icon, T20 (concept B simplified)</title>
<style>
body{{margin:0;background:#F5EEDF;color:#2B2119;font:15px/1.5 system-ui,sans-serif}}
main{{max-width:1180px;margin:0 auto;padding:32px 24px 64px}}
h1{{font-size:24px;margin:0 0 6px}} h2{{font-size:15px;margin:0 0 14px}}
p{{max-width:72ch;margin:0 0 10px}} code{{font-size:13px}}
.key{{display:flex;gap:28px;align-items:flex-start;flex-wrap:wrap;margin:24px 0 32px}}
.key figure{{margin:0}} .key figcaption{{font-size:13px;color:#5C4B3C;max-width:30ch;margin-top:6px}}
.scene{{padding:20px 24px 24px;margin:0 0 2px}}
.cells{{display:flex;gap:48px;flex-wrap:wrap}}
.mask{{display:block;font-size:12px;opacity:.75;margin-bottom:8px}}
.sizes{{display:flex;gap:24px;align-items:flex-end}}
figure{{margin:0;text-align:center}} figcaption{{font-size:11px;opacity:.75;margin-top:4px}}
.bar{{display:flex;gap:2px;margin:24px 0}} .bar div{{padding:10px 16px;display:flex;gap:12px;align-items:center;font-size:12px}}
ul{{padding-left:20px}}
</style></head><body><main>
<h1>Aristotle icon (T20, concept B simplified)</h1>
<p>The hand-on-chin bust from the engraving, silhouette first: curls and beard as one mass, the profile as a 3.2 dp line at 48 dp,
the fist under the chin with a clear gap, the hunched draped shoulder with one fold. Ink <code>#2B2119</code> on parchment
<code>#F3EAD8</code>. No text, no hatching. Furthest point from the centre: {extent_r:.1f} dp of the 33 dp safe-zone radius.</p>
<p>Sizes are CSS px = dp at 1x. On the Poco (about 2.75x) the 48 dp tile is about 132 device pixels. Approve on the phone.</p>
<div class="key">
<figure>{canvas_key()}<figcaption>108 dp canvas. Solid ring: 72 dp visible area. Dashed ring: 66 dp safe zone.</figcaption></figure>
<figure>{status_svg(96, "#2B2119")}<figcaption>Status-bar icon (<code>ic_stat_aristotle</code>), 24 dp, shown at 4x. White with alpha on the device; no fold at this size.</figcaption></figure>
</div>
{rows}
<div class="bar">
<div style="background:#1B1B1F;color:#E6E1E5">{status_svg(24, "#FFFFFF")} 24 dp, dark status bar</div>
<div style="background:#ECE6F0;color:#1D1B20">{status_svg(24, "#1D1B20")} 24 dp, light status bar</div>
</div>
<h2>PNG renders (3x, rendered with rsvg-convert)</h2>
<ul><li><a href="png/canvas.png">png/canvas.png</a></li>{pngs}<li><a href="png/status.png">png/status.png</a></li></ul>
<p>Sources: <code>aristotle-b2.svg</code>, <code>aristotle-b2-monochrome.svg</code>; everything here and the Android drawables come from
<code>build_icon.py</code>.</p>
</main></body></html>
"""


def renders():
    if not shutil.which("rsvg-convert"):
        print("rsvg-convert not found: PNG renders skipped")
        return
    os.makedirs(PNG, exist_ok=True)
    jobs = {"canvas": f'<svg xmlns="http://www.w3.org/2000/svg" width="648" height="648" viewBox="0 0 216 216">{canvas_key()}</svg>'}
    for s in SCENES:
        jobs[s[0]] = scene_svg(s)
    jobs["status"] = ('<svg xmlns="http://www.w3.org/2000/svg" width="288" height="96" viewBox="0 0 96 32">'
                      '<rect width="48" height="32" fill="#1B1B1F"/><rect x="48" width="48" height="32" fill="#ECE6F0"/>'
                      f'<g transform="translate(12,4)">{status_svg(24, "#FFFFFF")}</g>'
                      f'<g transform="translate(60,4)">{status_svg(24, "#1D1B20")}</g></svg>')
    tmp = os.path.join(os.environ.get("TMPDIR", "/tmp"), "t20-render.svg")
    for name, src in jobs.items():  # nested tile <svg>s inherit the root's namespace
        with open(tmp, "w") as f:
            f.write(src)
        subprocess.run(["rsvg-convert", tmp, "-o", os.path.join(PNG, name + ".png")], check=True)
        print("wrote", os.path.relpath(os.path.join(PNG, name + ".png"), ROOT))


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)
    print("wrote", os.path.relpath(path, ROOT))


def main():
    r, bbox = extent(LAUNCHER)
    assert r <= 33, f"outside the safe zone: {r:.2f}"
    print(f"max extent {r:.2f} / 33, bbox {tuple(round(v, 1) for v in bbox)}")
    master_svgs()
    android_res()
    write(os.path.join(HERE, "preview.html"), preview_html(r))
    renders()


if __name__ == "__main__":
    main()
