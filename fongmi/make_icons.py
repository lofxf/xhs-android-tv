#!/usr/bin/env python3
"""Generates the 小红书TV launcher icon (512 px) and Android TV / Fire TV banner (640x360).

Original artwork (red tile, white TV with a play mark) — not the official Xiaohongshu logo.
Usage: make_icons.py <out_dir> [font.ttc]
"""
import sys
from PIL import Image, ImageDraw, ImageFont

RED = (255, 36, 66, 255)
WHITE = (255, 255, 255, 255)
out = sys.argv[1]
font_path = sys.argv[2] if len(sys.argv) > 2 else "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
S = 4  # supersampling


def tv(d, x, y, w, color_bg, color_fg):
    """White TV glyph with a play triangle, top-left at (x, y), width w."""
    h = int(w * 0.68)
    r = int(w * 0.12)
    d.rounded_rectangle([x, y, x + w, y + h], radius=r, fill=color_fg)
    pad = int(w * 0.09)
    d.rounded_rectangle([x + pad, y + pad, x + w - pad, y + h - pad], radius=int(r * 0.6), fill=color_bg)
    cx, cy, t = x + w / 2, y + h / 2, w * 0.17
    d.polygon([(cx - t * 0.75, cy - t), (cx - t * 0.75, cy + t), (cx + t * 1.05, cy)], fill=color_fg)
    # stand
    sw = int(w * 0.36)
    d.rounded_rectangle([x + (w - sw) / 2, y + h + int(w * 0.06), x + (w + sw) / 2, y + h + int(w * 0.12)],
                        radius=int(w * 0.03), fill=color_fg)
    return h + int(w * 0.12)


def icon(size=512):
    n = size * S
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * 0.22), fill=RED)
    w = int(n * 0.62)
    total = int(w * 0.80)
    tv(d, (n - w) // 2, (n - total) // 2, w, RED, WHITE)
    return img.resize((size, size), Image.LANCZOS)


def banner(wd=640, ht=360):
    W, H = wd * S, ht * S
    img = Image.new("RGBA", (W, H), RED)
    d = ImageDraw.Draw(img)
    w = int(H * 0.50)
    total = int(w * 0.80)
    left = int(W * 0.08)
    tv(d, left, (H - total) // 2, w, RED, WHITE)
    font = ImageFont.truetype(font_path, int(H * 0.25))
    text = "小红书TV"
    tx = left + w + int(W * 0.05)
    bbox = d.textbbox((0, 0), text, font=font)
    ty = (H - (bbox[3] - bbox[1])) // 2 - bbox[1]
    d.text((tx, ty), text, font=font, fill=WHITE)
    return img.resize((wd, ht), Image.LANCZOS)


icon().save(f"{out}/xhs_icon.png")
banner().save(f"{out}/xhs_banner.png")
print("icons written to", out)
