#!/usr/bin/env python3
"""Honest Robin mascot: one bird, four poses, built from shared parts."""
import shutil
import subprocess
from pathlib import Path

OUT, BROWN, DARK = "#2A1A12", "#A0714F", "#7B4F36"
ORANGE, CREAM, BEAK = "#F26A2E", "#FBEEDB", "#F7B733"
EGG, PAPER, NEST, GLOW = "#8FD3C8", "#FFFDF8", "#C4925C", "#FF9A62"

S = f'stroke="{OUT}" stroke-width="8" stroke-linejoin="round" stroke-linecap="round"'
BODY = "M200,90 C275,90 330,165 330,255 C330,335 272,375 200,375 C128,375 70,335 70,255 C70,165 125,90 200,90 Z"

def shadow(rx=95):
    return f'<ellipse cx="200" cy="414" rx="{rx}" ry="11" fill="{OUT}" opacity=".16"/>'

TAIL = f'<path d="M98,288 C70,298 44,320 30,348 C52,346 70,340 82,331 C78,341 77,350 79,358 C97,346 110,327 114,308 Z" fill="{DARK}" {S}/>'

def leg(x):
    return (f'<path d="M{x},366 L{x},404 M{x},404 L{x-16},412 M{x},404 L{x},414 M{x},404 L{x+16},412" '
            f'fill="none" {S}/>')

LEGS = leg(170) + leg(232)

def body():
    return (
        f'<path d="{BODY}" fill="{BROWN}"/>'
        f'<clipPath id="b"><path d="{BODY}"/></clipPath>'
        f'<g clip-path="url(#b)">'
        f'<path d="M200,128 C255,128 294,170 294,225 C294,290 250,322 200,322 C150,322 106,290 106,225 C106,170 145,128 200,128 Z" fill="{ORANGE}"/>'
        f'<path d="M128,170 C140,150 160,140 176,138 C156,152 144,172 140,196 Z" fill="{GLOW}"/>'
        f'<ellipse cx="200" cy="378" rx="108" ry="78" fill="{CREAM}"/>'
        f'</g>'
        f'<path d="{BODY}" fill="none" {S}/>'
    )

# Drawn before the body, so the strands grow out from behind the outline.
TUFT = f'<path d="M200,90 L197,64 M212,92 L222,70 M188,92 L178,72" fill="none" {S}/>'

def face(brow="up"):
    eyes = "".join(
        f'<circle cx="{x}" cy="200" r="13" fill="{OUT}"/><circle cx="{x+4}" cy="195" r="4.5" fill="{PAPER}"/>'
        for x in (164, 236))
    if brow == "up":
        brows = "M148,176 Q163,166 179,174 M221,174 Q237,166 252,176"
    else:  # focused
        brows = "M148,174 Q164,170 180,176 M220,170 Q236,162 252,170"
    return (eyes +
            f'<path d="{brows}" fill="none" stroke="{OUT}" stroke-width="6" stroke-linecap="round"/>'
            f'<path d="M182,214 Q200,205 218,214 L200,242 Z" fill="{BEAK}" stroke="{OUT}" stroke-width="6" stroke-linejoin="round"/>')

WING_L = f'<path d="M82,215 C58,255 70,318 112,335 C120,300 118,250 104,212 C98,204 88,205 82,215 Z" fill="{DARK}" {S}/>'
WING_R = f'<path d="M318,215 C342,255 330,318 288,335 C280,300 282,250 296,212 C302,204 312,205 318,215 Z" fill="{DARK}" {S}/>'
# pledge: one wing raised, the other on the heart
WING_UP = f'<path d="M90,232 C56,206 40,160 52,122 C80,140 104,174 112,214 C108,228 98,236 90,232 Z" fill="{DARK}" {S}/>'
WING_HEART = (f'<path d="M314,214 C330,252 304,304 262,304 C232,304 208,288 202,266 C232,262 268,240 292,208 C300,200 310,204 314,214 Z" fill="{DARK}" {S}/>'
              f'<path d="M222,274 L240,268 M232,286 L252,278" fill="none" stroke="{OUT}" stroke-width="5" stroke-linecap="round"/>')

def stopwatch():
    cx, cy = 200, 320
    ticks = "".join(
        f'<line x1="{cx+dx*24}" y1="{cy+dy*24}" x2="{cx+dx*29}" y2="{cy+dy*29}" stroke="{OUT}" stroke-width="5" stroke-linecap="round"/>'
        for dx, dy in ((0,-1),(1,0),(0,1),(-1,0)))
    return (
        f'<path d="M146,262 L200,286 L254,262" fill="none" stroke="{OUT}" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>'
        f'<rect x="191" y="272" width="18" height="14" rx="3" fill="{BEAK}" stroke="{OUT}" stroke-width="6"/>'
        f'<circle cx="{cx}" cy="{cy}" r="38" fill="{PAPER}" stroke="{OUT}" stroke-width="8"/>'
        + ticks +
        f'<path d="M{cx},{cy} L{cx},{cy-22} M{cx},{cy} L{cx+15},{cy+9}" fill="none" stroke="{ORANGE}" stroke-width="6" stroke-linecap="round"/>'
        f'<circle cx="{cx}" cy="{cy}" r="5" fill="{OUT}"/>')

def notebook():
    rings = "".join(f'<path d="M{x},250 L{x},268" fill="none" stroke="{OUT}" stroke-width="5" stroke-linecap="round"/>' for x in (172, 191, 210, 229))
    return (
        f'<g transform="rotate(-4 202 306)">'
        f'<rect x="150" y="258" width="104" height="100" rx="8" fill="{EGG}" {S}/>'
        f'<rect x="170" y="282" width="64" height="24" rx="4" fill="{PAPER}" stroke="{OUT}" stroke-width="5"/>'
        f'<path d="M170,324 L234,324 M170,340 L212,340" fill="none" stroke="{OUT}" stroke-width="5" stroke-linecap="round"/>'
        + rings + '</g>'
        f'<path d="M82,214 C62,250 78,300 120,318 C140,326 158,318 164,304 C140,292 118,256 106,212 C100,204 88,204 82,214 Z" fill="{DARK}" {S}/>'
        f'<path d="M318,214 C338,250 322,300 280,318 C260,326 242,318 236,304 C260,292 282,256 294,212 C300,204 312,204 318,214 Z" fill="{DARK}" {S}/>')

PENCIL = (f'<g transform="rotate(38 262 118)">'
          f'<rect x="232" y="111" width="70" height="14" rx="2" fill="{BEAK}" stroke="{OUT}" stroke-width="5" stroke-linejoin="round"/>'
          f'<path d="M302,111 L320,118 L302,125 Z" fill="{PAPER}" stroke="{OUT}" stroke-width="5" stroke-linejoin="round"/>'
          f'<rect x="232" y="111" width="12" height="14" rx="2" fill="{ORANGE}" stroke="{OUT}" stroke-width="5"/>'
          f'</g>')

def nest():
    twigs = ("M92,372 L140,392 M150,364 L196,402 M214,404 L262,366 M268,392 L312,370 "
             "M120,404 L170,414 M232,416 L286,404 M176,380 L226,378")
    return (
        f'<path d="M50,336 L24,318 M352,338 L380,320" fill="none" {S}/>'
        f'<path d="M52,330 C52,398 122,428 200,428 C278,428 348,398 348,330 C300,352 100,352 52,330 Z" fill="{NEST}" {S}/>'
        f'<path d="{twigs}" fill="none" stroke="{OUT}" stroke-width="5" stroke-linecap="round"/>'
        f'<ellipse cx="318" cy="322" rx="17" ry="21" transform="rotate(18 318 322)" fill="{EGG}" stroke="{OUT}" stroke-width="6"/>')

def svg(inner, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 440" role="img" aria-label="{title}">'
            f'<title>{title}</title>{inner}</svg>')

POSES = {
    "promise": svg(shadow() + TAIL + LEGS + TUFT + body() + WING_UP + WING_HEART + face("up"),
                   "Honest Robin with one wing raised and one wing on its heart"),
    "time": svg(shadow() + TAIL + LEGS + TUFT + body() + WING_L + WING_R + stopwatch() + face("up"),
                "Honest Robin wearing a stopwatch"),
    "notes": svg(shadow() + TAIL + LEGS + TUFT + body() + PENCIL + notebook() + face("focus"),
                 "Honest Robin holding a notebook, pencil tucked behind its head"),
    "nest": svg(shadow(150) + TUFT + body() + WING_L + WING_R + nest() + face("up"),
                "Honest Robin sitting in its own nest"),
}

# The head alone, for small sizes (the favicon, the wordmark): drawn on its own 64-unit grid,
# with a thicker outline and no highlights, so it still reads at 16 pixels.
F_BODY = "M32,6 C47,6 57,20 57,36 C57,51 46,59 32,59 C18,59 7,51 7,36 C7,20 17,6 32,6 Z"
FACE = (
    f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><title>Honest Robin</title>'
    f'<clipPath id="c"><path d="{F_BODY}"/></clipPath><path d="{F_BODY}" fill="{BROWN}"/>'
    f'<g clip-path="url(#c)"><circle cx="32" cy="34" r="19" fill="{ORANGE}"/><ellipse cx="32" cy="62" rx="21" ry="14" fill="{CREAM}"/></g>'
    f'<path d="{F_BODY}" fill="none" stroke="{OUT}" stroke-width="3.5"/>'
    f'<circle cx="24.5" cy="28" r="3.6" fill="{OUT}"/><circle cx="39.5" cy="28" r="3.6" fill="{OUT}"/>'
    f'<path d="M27,33 Q32,31 37,33 L32,41 Z" fill="{BEAK}" stroke="{OUT}" stroke-width="2.4" stroke-linejoin="round"/></svg>')

# Written here, and into the web app, which can't reach docs/ when it's built.
HERE = Path(__file__).resolve().parent
APP = HERE.parents[2] / "frontend/src/design/robin"
APP.mkdir(exist_ok=True)
for name, s in [*POSES.items(), ("face", FACE)]:
    for folder in (HERE, APP):
        (folder / f"robin-{name}.svg").write_text(s)
(HERE.parents[2] / "frontend/public/favicon.svg").write_text(FACE)

# contact sheet for checking: light stage and dark stage
cells = "".join(
    f'<g transform="translate({i*400},{row*440})">{s.replace("<svg ", "<svg width=\"400\" height=\"440\" ")}</g>'
    for row in (0, 1) for i, s in enumerate(POSES.values()))
sheet = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1600 880">'
         f'<rect width="1600" height="440" fill="#CDEBE6"/><rect y="440" width="1600" height="440" fill="#2F6B70"/>{cells}</svg>')
# clip ids must be unique inside one document
for i in range(8):
    sheet = sheet.replace('id="b"', f'id="b{i}"', 1).replace('url(#b)', f'url(#b{i})', 1)
(HERE / "sheet.svg").write_text(sheet)
if shutil.which("rsvg-convert"):
    subprocess.run(["rsvg-convert", "-w", "1600", HERE / "sheet.svg", "-o", HERE / "sheet.png"], check=True)
print("ok", {k: len(v) for k, v in POSES.items()})
