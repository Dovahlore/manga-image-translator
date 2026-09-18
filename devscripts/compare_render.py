"""Compare App-API output vs Web-UI-config output for the same page.

App default config (app_api/settings.py DEFAULT_CONFIG):
  detector ctd / detection_size 2048 / ocr mocr+merge / inpainter lama_large 1024
  render {renderer: default, font_color: "000000"}
Web UI default config (front/app/App.tsx buildTranslationConfig):
  detector "default" / detection_size 1536 / box_threshold 0.7 / ocr 48px
  inpainter "default" 2048 / mask_dilation_offset 30 / render.direction auto
"""
from __future__ import annotations

import base64
import io
import json
from pathlib import Path

import numpy as np
import requests
from PIL import Image
from scipy import ndimage

WEB_CFG = {
    "detector": {"detector": "default", "detection_size": 1536, "box_threshold": 0.7, "unclip_ratio": 2.3},
    "render": {"direction": "auto"},
    "translator": {"translator": "deepseek", "target_lang": "CHS"},
    "inpainter": {"inpainter": "default", "inpainting_size": 2048},
    "mask_dilation_offset": 30,
}
APP_CFG = {
    "translator": {"translator": "deepseek", "target_lang": "CHS"},
    "detector": {"detector": "ctd", "detection_size": 2048},
    "ocr": {"ocr": "mocr", "use_mocr_merge": True},
    "inpainter": {"inpainter": "lama_large", "inpainting_size": 1024, "inpainting_precision": "bf16"},
    "render": {"renderer": "default", "font_color": "000000"},
}

OUT = Path("epub_test/compare")


def run_engine(img: Path, cfg: dict) -> tuple[Image.Image, list]:
    r = requests.post("http://127.0.0.1:8010/translate/with-form/page",
                      files={"image": (img.name, img.read_bytes(), "image/jpeg")},
                      data={"config": json.dumps(cfg)}, timeout=900)
    r.raise_for_status()
    j = r.json()
    return Image.open(io.BytesIO(base64.b64decode(j["image_b64"]))).convert("RGB"), j["result"]["translations"]


def glyph_heights(arr: np.ndarray, boxes: list[tuple[int, int, int, int]], mask: np.ndarray | None = None) -> list[int]:
    """Median height of dark connected components inside each box (approx font size, px)."""
    out = []
    for (x1, y1, x2, y2) in boxes:
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(arr.shape[1], x2), min(arr.shape[0], y2)
        if x2 - x1 < 4 or y2 - y1 < 4:
            continue
        patch = arr[y1:y2, x1:x2]
        dark = patch < 128
        if mask is not None:
            dark = dark & mask[y1:y2, x1:x2]
        lab, n = ndimage.label(dark)
        if n == 0:
            continue
        objs = ndimage.find_objects(lab)
        hs = []
        for sl in objs:
            h = sl[0].stop - sl[0].start
            w = sl[1].stop - sl[1].start
            if 5 <= h <= 150 and 3 <= w <= 150:
                hs.append(h)
        if hs:
            out.append(int(np.median(hs)))
    return out


def ink_stats(img_arr: np.ndarray, orig_arr: np.ndarray, boxes) -> dict:
    """Count newly drawn near-black stroke pixels inside boxes (rendered text ink)."""
    new_ink = (img_arr < 40) & (orig_arr > 120)
    glyphs = []
    total = 0
    for (x1, y1, x2, y2) in boxes:
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(img_arr.shape[1], x2), min(img_arr.shape[0], y2)
        if x2 - x1 < 4 or y2 - y1 < 4:
            continue
        sub = new_ink[y1:y2, x1:x2]
        total += int(sub.sum())
        lab, n = ndimage.label(sub)
        for sl in ndimage.find_objects(lab) if n else []:
            h = sl[0].stop - sl[0].start
            w = sl[1].stop - sl[1].start
            if 5 <= h <= 200 and 3 <= w <= 200:
                glyphs.append(h)
    glyphs.sort()
    return {
        "ink_px": total,
        "glyph_n": len(glyphs),
        "glyph_h_median": int(np.median(glyphs)) if glyphs else 0,
        "glyph_h_p90": int(np.percentile(glyphs, 90)) if glyphs else 0,
    }


def ascii_crop(img: Image.Image, box, cols: int = 100, title: str = "") -> str:
    x1, y1, x2, y2 = box
    crop = img.convert("L").crop((max(0, x1 - 5), max(0, y1 - 5), x2 + 5, y2 + 5))
    w = cols
    h = max(6, int(crop.height / max(1, crop.width) * w * 0.5))
    arr = np.asarray(crop.resize((w, h), Image.LANCZOS))
    ramp = "@%#*+=-:. "
    lines = [f"--- {title} box={box} ---"]
    for row in arr:
        lines.append("".join(ramp[min(9, int((255 - v) / 256 * 10))] for v in row))
    return "\n".join(lines)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    page = Path("epub_test/orig/page-010.jpg")
    orig = Image.open(page).convert("RGB")
    orig_arr = np.asarray(orig.convert("L")).astype(np.int16)

    print("running engine with web-UI config ...", flush=True)
    web_img, web_blocks = run_engine(page, WEB_CFG)
    web_img.save(OUT / "page-010.web.png")

    print("running engine with App-API config ...", flush=True)
    app_img, app_blocks = run_engine(page, APP_CFG)
    app_img.save(OUT / "page-010.app.png")

    web_arr = np.asarray(web_img.convert("L")).astype(np.int16)
    app_arr = np.asarray(app_img.convert("L")).astype(np.int16)

    def boxes(blocks):
        return [(int(b["minX"]), int(b["minY"]), int(b["maxX"]), int(b["maxY"])) for b in blocks]

    web_changed = np.abs(web_arr - orig_arr) > 60
    app_changed = np.abs(app_arr - orig_arr) > 60

    rows = []
    for name, blocks, arr_img, changed in (
            ("orig(JP)", None, orig_arr, None),
            ("web-cfg", web_blocks, web_arr, web_changed),
            ("app-cfg", app_blocks, app_arr, app_changed)):
        if blocks is None:
            # 用 web 的框量原图
            bs = boxes(web_blocks)
        else:
            bs = boxes(blocks)
        if changed is None:
            hs = glyph_heights(arr_img, bs)
        else:
            hs = glyph_heights(arr_img, bs, mask=changed)
        rows.append((name, len(bs), hs))

    for name, nbox, hs in rows:
        hs_s = sorted(hs)
        med = int(np.median(hs)) if hs else 0
        print(f"{name:9s} boxes={nbox:3d} glyph_height_median={med:3d}px  per-box={hs_s}")

    print("\n== rendered ink (new near-black strokes inside boxes) ==")
    print("web:", ink_stats(web_arr, orig_arr, boxes(web_blocks)))
    print("app:", ink_stats(app_arr, orig_arr, boxes(app_blocks)))

    # 直接看同一块的渲染效果
    b = boxes(web_blocks)[0]
    print("\n" + ascii_crop(orig, b, title="ORIGINAL"))
    print("\n" + ascii_crop(web_img, b, title="WEB-CFG"))
    print("\n" + ascii_crop(app_img, b, title="APP-CFG"))

    # 每块面积对比
    wb, ab = boxes(web_blocks), boxes(app_blocks)
    print(f"\nweb boxes area sum = {sum((b[2]-b[0])*(b[3]-b[1]) for b in wb)}")
    print(f"app boxes area sum = {sum((b[2]-b[0])*(b[3]-b[1]) for b in ab)}")
    for i, b in enumerate(wb[:8]):
        print(f"  web box{i}: {b} {b[2]-b[0]}x{b[3]-b[1]}")
    for i, b in enumerate(ab[:8]):
        print(f"  app box{i}: {b} {b[2]-b[0]}x{b[3]-b[1]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
