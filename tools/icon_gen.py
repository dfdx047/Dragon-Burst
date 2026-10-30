#!/usr/bin/env python3
"""Dragon Burst launcher icon generator (pure Python + numpy + Pillow).

Concept (2026-10-30, the owner's request: an icon that says Dragon Ball Z): an orange crystal orb with a red star,
held in a golden flame aura that rises like a Super Saiyan's, with blue-white sparks crackling around it (the
Super Saiyan 2 lightning) and embers flying up. Everything is drawn here: no characters, official logos or text.

Outputs (under app/src/main/res):
  drawable/ic_launcher_background.xml     dark radial gradient (vector)
  drawable-<dpi>/ic_launcher_foreground.png   108 dp, real alpha (glow alpha from
                                              luminance, letters and rocks opaque)
  drawable-<dpi>/ic_launcher_monochrome.png   108 dp, letters silhouette + ring
  mipmap-<dpi>/ic_launcher.png, ic_launcher_round.png   legacy 48 dp
and a preview sheet.

Usage: icon_gen.py [res_dir] [preview.png]
"""
import math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "app", "src", "main", "res")
PREVIEW = sys.argv[2] if len(sys.argv) > 2 else "icon_preview.png"

W = 1728          # supersampled canvas = 108 dp * 16
U = W / 108.0     # 1 dp in pixels
DENS = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]


# ---------------------------------------------------------------- helpers
def P(x, y):
    return (x * U, y * U)


def mask_poly(pts, size=W):
    im = Image.new("L", (size, size), 0)
    ImageDraw.Draw(im).polygon([tuple(p) for p in pts], fill=255)
    return np.asarray(im, np.float32) / 255.0


def mask_lines(polys, width, size=W):
    im = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(im)
    for pl in polys:
        d.line([tuple(p) for p in pl], fill=255, width=max(1, int(width)), joint="curve")
    return np.asarray(im, np.float32) / 255.0


def _box(a, r, axis):
    """box filter of radius r along axis (edge-clamped), via cumulative sums"""
    if r < 1:
        return a
    pad = [(0, 0)] * a.ndim
    pad[axis] = (r + 1, r)
    c = np.cumsum(np.pad(a, pad, mode="edge"), axis=axis, dtype=np.float64)
    n = a.shape[axis]
    hi = np.take(c, np.arange(2 * r + 1, 2 * r + 1 + n), axis=axis)
    lo = np.take(c, np.arange(0, n), axis=axis)
    return ((hi - lo) / (2 * r + 1)).astype(np.float32)


def blur(m, s):
    """approximate gaussian blur (sigma s px): 3 box passes; big sigmas at reduced resolution"""
    if s < 0.3:
        return m
    H, Wd = m.shape
    k = 1
    for cand in (8, 6, 4, 3, 2):
        if s / cand >= 3 and H % cand == 0 and Wd % cand == 0:
            k = cand
            break
    a = m.reshape(H // k, k, Wd // k, k).mean((1, 3)) if k > 1 else m
    sk = s / k
    r = max(1, int(round((math.sqrt(4 * sk * sk + 1) - 1) / 2)))
    for _ in range(3):
        a = _box(_box(a, r, 0), r, 1)
    if k > 1:
        a = np.asarray(Image.fromarray(a.astype(np.float32), "F").resize((Wd, H), Image.BICUBIC), np.float32)
    return a


def col(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)], np.float32)


def add(img, m, c, k=1.0):
    img += (m[..., None] * k) * c[None, None, :]


def over(img, m, c):
    m = np.clip(m, 0, 1)[..., None]
    img *= (1 - m)
    img += m * (c if np.ndim(c) == 3 else c[None, None, :])


def fbm(seed, size=W, octaves=5, base=6):
    rng = np.random.default_rng(seed)
    out = np.zeros((size, size), np.float32)
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        n = base * (2 ** o)
        a = rng.random((n + 1, n + 1)).astype(np.float32)
        out += amp * np.asarray(Image.fromarray(a, "F").resize((size, size), Image.BICUBIC), np.float32)
        tot += amp
        amp *= 0.5
    return np.clip(out / tot, 0, 1)


def tonemap(x):
    return 1 - np.exp(-x * 1.25)


def untonemap(t):
    return -np.log(1 - np.clip(t, 0, 0.999)) / 1.25


def ramp(v, stops):
    v = np.clip(v, 0, 1)
    xs = np.array([s[0] for s in stops], np.float32)
    cs = np.stack([col(s[1]) for s in stops])
    return np.stack([np.interp(v, xs, cs[:, i]) for i in range(3)], -1).astype(np.float32)


def grid(size=W):
    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    k = size / 108.0
    return (x + 0.5) / k, (y + 0.5) / k


X, Y = grid()
CX, CY = 54.0, 54.0
GOLD, EMBER, HOT, WHITE = col("#FFD63C"), col("#FF8A1F"), col("#FFF2B8"), col("#FFFFFF")
RED, LAV = col("#C0300A"), col("#9678D6")

# ---------------------------------------------------------------- background (shared with the XML)
BG_RADIUS = 76.4  # centre to corner


def _bg_exact(r):
    r0 = r / 72.5
    lin = col("#040207")[None, :] + (np.clip(1 - r0, 0, 1) ** 2)[:, None] * col("#3A1A5E")[None, :] * 0.85
    vig = np.clip(1 - 0.6 * (r / 64) ** 2.4, 0.15, 1)
    return tonemap(lin * vig[:, None])


BG_OFFS = np.array([0, 0.12, 0.25, 0.4, 0.56, 0.75, 1.0], np.float32)
BG_STOPS = np.round(_bg_exact(BG_OFFS * BG_RADIUS) * 255).astype(int)  # sRGB 0..255


def bg_image(size):
    """exactly what Android draws for ic_launcher_background.xml (piecewise-linear radial)"""
    x, y = grid(size)
    t = np.clip(np.hypot(x - CX, y - CY) / BG_RADIUS, 0, 1)
    return np.stack([np.interp(t, BG_OFFS, BG_STOPS[:, i] / 255.0) for i in range(3)], -1).astype(np.float32)


def bg_xml():
    items = "\n".join(f'                <item android:offset="{o:.2f}" android:color="#FF{c[0]:02X}{c[1]:02X}{c[2]:02X}" />'
                      for o, c in zip(BG_OFFS, BG_STOPS))
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Fundo do icone Dragon Burst: violeta no centro, quase preto nas pontas. Gerado por tools/icon_gen.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0 L108,0 L108,108 L0,108Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="54" android:centerY="54" android:gradientRadius="{BG_RADIUS}">
{items}
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''


# ---------------------------------------------------------------- the orb, the aura, the sparks
ORB_X, ORB_Y, ORB_R = 54.0, 56.5, 17.5   # dp; the aura and sparks stay inside the 66 dp safe circle
STAR_R = 8.2


def star_pts(cx, cy, r, inner=0.43, rot=-math.pi / 2):
    pts = []
    for i in range(10):
        rad = r if i % 2 == 0 else r * inner
        a = rot + i * math.pi / 5
        pts.append(P(cx + rad * math.cos(a), cy + rad * math.sin(a)))
    return pts


def aura_mask(scale, seed, sharp):
    """flame aura: spikes around the orb, longer and narrower upward (the aura rises)"""
    rng = np.random.default_rng(seed)
    dx, dy = X - ORB_X, Y - ORB_Y
    rr = np.hypot(dx, dy)
    ang = np.arctan2(dy, dx)                 # -pi/2 = up
    up = np.clip(-np.sin(ang), 0, 1)        # 1 straight up, 0 at the sides and below
    n = 13
    phase = rng.uniform(0, 2 * math.pi)
    spikes = np.zeros_like(ang)
    for k, amp in ((n, 1.0), (n * 2 + 1, 0.35)):
        spikes += amp * np.clip(np.cos(ang * k + phase), 0, 1) ** sharp
    spikes /= 1.35
    wob = np.zeros_like(ang)
    for k in range(2, 9):
        wob += np.sin(ang * k + rng.uniform(0, 6.3)) * rng.uniform(0.3, 1.0) / k
    reach = (ORB_R + 3.0 + 3.5 * up + spikes * (3 + 8.5 * up ** 1.5) + wob * 1.0) * scale
    # flames lean upward: the edge is softer and taller above the orb
    edge = 0.9 + 1.6 * up
    return np.clip((reach - rr) / edge, 0, 1).astype(np.float32), rr, ang, up


def bolt(rng, x0, y0, x1, y1, depth=6, jag=2.6):
    pts = [(x0, y0), (x1, y1)]
    for d in range(depth):
        nxt = [pts[0]]
        for (ax, ay), (bx, by) in zip(pts, pts[1:]):
            mx, my = (ax + bx) / 2, (ay + by) / 2
            lx, ly = bx - ax, by - ay
            ln = math.hypot(lx, ly) or 1
            off = rng.uniform(-1, 1) * jag * ln / 9
            nxt += [(mx - ly / ln * off, my + lx / ln * off), (bx, by)]
        pts = nxt
    return [P(x, y) for x, y in pts]


def render():
    """returns (tonemapped RGB at W, opaque mask at W, monochrome mask at W)"""
    rng = np.random.default_rng(29)
    img = untonemap(bg_image(W))
    opaque = np.zeros((W, W), np.float32)

    # aura: three layers, outer orange glow, gold flames, white-gold core
    outer, rr, ang, up = aura_mask(1.08, 3, 8)
    mid, _, _, _ = aura_mask(1.0, 5, 12)
    core, _, _, _ = aura_mask(0.86, 7, 16)
    streak = fbm(11, octaves=4, base=4)
    # vertical licks inside the flames
    lick = np.asarray(Image.fromarray(streak, "F").resize((W // 6, W), Image.BICUBIC).resize((W, W), Image.BICUBIC), np.float32)
    lick = 0.65 + 0.7 * np.clip(lick - 0.35, 0, 1)
    add(img, blur(outer, 4.0 * U), col("#FF6A00"), 0.5)
    add(img, blur(outer, 1.0 * U) * lick, col("#FF7A12"), 0.8)
    add(img, blur(mid, 0.45 * U) * lick, col("#FFB800"), 1.0)
    add(img, blur(core, 0.6 * U) * lick, col("#FFE070"), 0.55)
    mono_aura = mid

    # sparks: blue-white lightning crackling around the aura
    bolts = []
    for (a0, a1, r0, r1) in ((-2.5, -2.0, 22, 31.5), (-0.4, 0.2, 23, 31), (2.25, 2.75, 22, 29.5)):
        x0, y0 = ORB_X + r0 * math.cos(a0), ORB_Y + r0 * math.sin(a0)
        x1, y1 = ORB_X + r1 * math.cos(a1), ORB_Y + r1 * math.sin(a1)
        bolts.append(bolt(rng, x0, y0, x1, y1))
    bm = mask_lines(bolts, 0.7 * U)
    add(img, blur(bm, 2.2 * U), col("#3D8BFF"), 1.6)
    add(img, blur(bm, 0.6 * U), col("#9FD4FF"), 1.4)
    add(img, bm, WHITE, 1.2)

    # embers flying up
    for _ in range(26):
        a = rng.uniform(-math.pi, 0)
        r = rng.uniform(19, 31)
        ex, ey = ORB_X + r * math.cos(a) * 1.05, ORB_Y + r * math.sin(a)
        if math.hypot(ex - 54, ey - 54) > 31.5:
            continue
        s = rng.uniform(0.25, 0.6)
        e = np.exp(-(((X - ex) / s) ** 2 + ((Y - ey) / (s * 2.4)) ** 2)).astype(np.float32)
        add(img, e, HOT if rng.random() < 0.5 else GOLD, rng.uniform(0.8, 1.6))

    # the orb: an orange crystal sphere
    dx, dy = X - ORB_X, Y - ORB_Y
    d = np.hypot(dx, dy) / ORB_R
    disc = np.clip((1 - d) * ORB_R * U / 1.5, 0, 1).astype(np.float32)
    add(img, blur(disc, 1.6 * U), GOLD, 1.0)                     # rim light from the aura
    z = np.sqrt(np.clip(1 - d ** 2, 0, 1))
    lx, ly, lz = -0.45, -0.6, 0.66                                # light from the upper left
    nx, ny = dx / ORB_R, dy / ORB_R
    lam = np.clip(nx * lx + ny * ly + z * lz, 0, 1)
    body = ramp(0.25 + 0.75 * lam, [(0, "#8A2A00"), (0.35, "#D9540A"), (0.7, "#FF9A1E"), (1, "#FFC85A")])
    # light passing through the crystal pools on the far (lower right) side
    caustic = np.exp(-(((nx - 0.38) / 0.42) ** 2 + ((ny - 0.45) / 0.30) ** 2)).astype(np.float32)
    body = body + caustic[..., None] * col("#FFD27A")[None, None, :] * 0.9
    edge_dark = np.clip((d - 0.82) / 0.18, 0, 1) ** 1.5
    body = body * (1 - 0.55 * edge_dark[..., None])
    orb = untonemap(np.clip(body * 0.92, 0, 0.99))
    over(img, disc, orb)
    opaque = np.maximum(opaque, disc)

    # the red star, with a darker bevel
    sm = mask_poly(star_pts(ORB_X + 0.4, ORB_Y + 1.0, STAR_R))
    sm = np.asarray(Image.fromarray(sm, "F").resize((W, W)), np.float32)
    sm = blur(sm, 0.12 * U) * disc
    inner = np.clip(sm - blur(1 - sm, 0.35 * U) * 2.2, 0, 1)
    over(img, sm, untonemap(np.full(3, 0.5, np.float32) * col("#7A0A0A") * 1.6))
    over(img, inner, untonemap(col("#E0261C") * 0.95))
    add(img, inner * np.clip(-(Y - ORB_Y) / STAR_R, 0, 1) * 0.6, col("#FF6A40"), 0.6)

    # glassy highlights: a soft window reflection and a hard specular dot
    hl = np.exp(-((((X - (ORB_X - 6.0)) * 0.8 + (Y - (ORB_Y - 7.8)) * 0.6) / 4.4) ** 2 +
                  ((-(X - (ORB_X - 6.0)) * 0.6 + (Y - (ORB_Y - 7.8)) * 0.8) / 2.1) ** 2)).astype(np.float32)
    add(img, hl * disc, WHITE, 2.2)
    dot = np.exp(-(((X - (ORB_X + 6.8)) / 0.9) ** 2 + ((Y - (ORB_Y + 7.2)) / 0.9) ** 2)).astype(np.float32)
    add(img, dot * disc, HOT, 1.4)
    # thin dark outline so the orb reads on the flames at 48 px
    ring = np.clip(1 - np.abs(d - 1.0) * ORB_R * U / (0.55 * U), 0, 1).astype(np.float32)
    over(img, ring * 0.7, untonemap(col("#5A1A00") * 0.9))

    # monochrome (themed icons): the aura's silhouette, a gap, the orb with the star cut out
    gap = np.clip((d - 1.0) * ORB_R / 1.6, 0, 1)
    mono = np.maximum(mono_aura * np.clip((d - 1.12) * ORB_R / 0.6, 0, 1) * 0.55, disc * (1 - sm))
    mono = np.where(d < 1.0, disc * (1 - sm), mono)
    return tonemap(np.clip(img, 0, None)), opaque, mono.astype(np.float32)


# ---------------------------------------------------------------- outputs
def down(a, px):
    if a.ndim == 2:
        return np.asarray(Image.fromarray(a.astype(np.float32), "F").resize((px, px), Image.LANCZOS), np.float32)
    return np.stack([down(a[..., i], px) for i in range(a.shape[2])], -1)


def to_img(a):
    return Image.fromarray((np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8))


def foreground(F, O, px):
    """unmix the composite F over the known background into colour + alpha.
    Glow alpha comes from how much brighter than the background a pixel is;
    letters and rocks (opaque mask) stay fully opaque."""
    f, o, b = np.clip(down(F, px), 0, 1), np.clip(down(O, px), 0, 1), bg_image(px)
    a = np.max(np.clip((f - b) / np.maximum(1 - b, 1e-3), 0, 1), -1)
    a = np.maximum(a, o)
    c = (f - (1 - a[..., None]) * b) / np.maximum(a[..., None], 1e-4)
    c = np.where(a[..., None] > 1e-3, c, 0)
    return to_img(np.dstack([np.clip(c, 0, 1), a]))


def composite(F, px):
    return to_img(np.clip(down(F, px), 0, 1)).convert("RGBA")


def shape_mask(px, kind):
    s = 4
    m = Image.new("L", (px * s, px * s), 0)
    d = ImageDraw.Draw(m)
    if kind == "circle":
        d.ellipse([0, 0, px * s - 1, px * s - 1], fill=255)
    else:  # squircle
        n, pts = 5.0, []
        for i in range(720):
            t = 2 * math.pi * i / 720
            c, si = math.cos(t), math.sin(t)
            x = abs(c) ** (2 / n) * np.sign(c); y = abs(si) ** (2 / n) * np.sign(si)
            pts.append(((x + 1) / 2 * px * s, (y + 1) / 2 * px * s))
        d.polygon(pts, fill=255)
    return m.resize((px, px), Image.LANCZOS)


def masked(img, kind):
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), shape_mask(img.size[0], kind))
    return out


def visible(F, px):
    """what a launcher shows: the middle 72 of the 108 dp canvas, at px"""
    full = composite(F, round(px * 108 / 72))
    a = (full.size[0] - px) // 2
    return full.crop((a, a, a + px, a + px))


def save_res(F, O, Mo):
    dr = os.path.join(RES, "drawable"); os.makedirs(dr, exist_ok=True)
    open(os.path.join(dr, "ic_launcher_background.xml"), "w").write(bg_xml())
    for q, k in DENS:
        px = round(108 * k)
        d = os.path.join(RES, f"drawable-{q}"); os.makedirs(d, exist_ok=True)
        foreground(F, O, px).save(os.path.join(d, "ic_launcher_foreground.png"), optimize=True)
        mono = np.clip(down(Mo, px), 0, 1)
        Image.fromarray(np.dstack([np.full((px, px, 3), 255, np.uint8), (mono * 255 + 0.5).astype(np.uint8)]), "RGBA") \
            .save(os.path.join(d, "ic_launcher_monochrome.png"), optimize=True)
        lp = round(48 * k)
        m = os.path.join(RES, f"mipmap-{q}"); os.makedirs(m, exist_ok=True)
        v = visible(F, lp)
        masked(v, "squircle").save(os.path.join(m, "ic_launcher.png"), optimize=True)
        masked(v, "circle").save(os.path.join(m, "ic_launcher_round.png"), optimize=True)


def font(sz):
    for f in ("/usr/share/fonts/opentype/inter/Inter-SemiBold.otf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(f):
            return ImageFont.truetype(f, sz)
    return ImageFont.load_default()


def preview(F, O, Mo):
    """large in the squircle, plus 48 px on light and dark, 96 px, round/squircle masks, themed"""
    BIG, pad = 400, 30
    sheet = Image.new("RGBA", (pad * 4 + BIG + 2 * 300, BIG + 2 * pad + 160), (22, 10, 38, 255))
    d = ImageDraw.Draw(sheet)
    fl, fs = font(22), font(15)
    cream = (232, 222, 196, 255)
    d.text((pad, 14), "Dragon Burst - launcher icon", font=fl, fill=(255, 214, 60, 255))
    sheet.alpha_composite(masked(visible(F, BIG), "squircle"), (pad, 50))
    d.text((pad, 56 + BIG), "squircle mask", font=fs, fill=cream)
    # adaptive layers recombined: fg PNG over the vector background, full 108 dp with safe zone
    x1 = pad * 2 + BIG
    fg = foreground(F, O, 300)
    bgl = to_img(bg_image(300)).convert("RGBA")
    layered = Image.alpha_composite(bgl, fg)
    dd = ImageDraw.Draw(layered)
    for r_dp, colr in [(33, (255, 255, 255, 150)), (36, (120, 200, 255, 150))]:
        s0, s1 = 300 * (54 - r_dp) / 108, 300 * (54 + r_dp) / 108
        dd.ellipse([s0, s0, s1, s1], outline=colr)
    sheet.alpha_composite(layered, (x1, 50))
    d.text((x1, 356), "108 dp layers (fg PNG + bg), 66/72 dp", font=fs, fill=cream)
    x2 = x1 + 300 + pad
    sheet.alpha_composite(masked(visible(F, 300), "circle"), (x2, 50))
    d.text((x2, 356), "round mask", font=fs, fill=cream)
    # bottom row
    y0 = 400 + 2 * pad + 40
    d.rounded_rectangle((pad, y0 - 14, pad + 210, y0 + 76), 14, fill=(236, 233, 228, 255))
    for i, k in enumerate(("squircle", "circle")):
        sheet.alpha_composite(masked(visible(F, 48), k), (pad + 30 + i * 90, y0 + 7))
    d.text((pad, y0 + 84), "48 px on light", font=fs, fill=cream)
    xb = pad + 240
    d.rounded_rectangle((xb, y0 - 14, xb + 210, y0 + 76), 14, fill=(12, 12, 14, 255))
    for i, k in enumerate(("squircle", "circle")):
        sheet.alpha_composite(masked(visible(F, 48), k), (xb + 30 + i * 90, y0 + 7))
    d.text((xb, y0 + 84), "48 px on dark", font=fs, fill=cream)
    xc = xb + 240
    sheet.alpha_composite(masked(visible(F, 96), "squircle"), (xc, y0 - 16))
    sheet.alpha_composite(masked(visible(F, 96), "circle"), (xc + 116, y0 - 16))
    d.text((xc, y0 + 84), "96 px", font=fs, fill=cream)
    xd = xc + 250
    mono = np.clip(down(Mo, round(96 * 108 / 72)), 0, 1)
    a = (mono.shape[0] - 96) // 2
    mono = mono[a:a + 96, a:a + 96]
    th = np.dstack([np.full((96, 96), v) for v in (0.13, 0.20, 0.30)]) * (1 - mono[..., None]) + \
        np.dstack([np.full((96, 96), v) for v in (0.80, 0.86, 0.98)]) * mono[..., None]
    sheet.alpha_composite(masked(to_img(th).convert("RGBA"), "circle"), (xd, y0 - 16))
    d.text((xd, y0 + 84), "themed (mono)", font=fs, fill=cream)
    sheet.convert("RGB").save(PREVIEW)


def main():
    F, O, Mo = render()
    save_res(F, O, Mo)
    preview(F, O, Mo)
    print("->", PREVIEW)


if __name__ == "__main__":
    main()
