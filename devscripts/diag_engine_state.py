"""Diagnose engine shared-state leak + compare App vs Web config rendering.

Sequence:
  1. non-streaming page call with App config      -> image size?
  2. non-streaming page call with Web-UI config   -> image size?
  3. web-UI streaming call (/translate/with-form/image/stream/web)   [sets _is_streaming_mode=True]
  4. non-streaming page call with App config again -> image size?

If step 4 collapses to 1x1, the web UI permanently poisons the shared
MangaTranslator instance for every non-streaming API client.

Run this right after `docker restart mit-engine`.
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

BASE = "http://127.0.0.1:8010"
PAGE = Path("epub_test/orig/page-010.jpg")
OUT = Path("epub_test/compare")
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


def page_call(cfg: dict) -> tuple[Image.Image, list]:
    r = requests.post(f"{BASE}/translate/with-form/page",
                      files={"image": (PAGE.name, PAGE.read_bytes(), "image/jpeg")},
                      data={"config": json.dumps(cfg)}, timeout=900)
    r.raise_for_status()
    j = r.json()
    img = Image.open(io.BytesIO(base64.b64decode(j["image_b64"]))).convert("RGB")
    return img, j["result"]["translations"]


def stream_web_call(cfg: dict) -> tuple[int, int]:
    r = requests.post(f"{BASE}/translate/with-form/image/stream/web",
                      files={"image": (PAGE.name, PAGE.read_bytes(), "image/jpeg")},
                      data={"config": json.dumps(cfg)}, timeout=900)
    return r.status_code, len(r.content)


def glyph_h(mask: np.ndarray, lo=5, hi=400) -> int:
    lab, n = ndimage.label(mask)
    hs = []
    for sl in ndimage.find_objects(lab) if n else []:
        h = sl[0].stop - sl[0].start
        w = sl[1].stop - sl[1].start
        if lo <= h <= hi and 2 <= w <= hi:
            hs.append(h)
    return int(np.median(hs)) if hs else 0


def report(name: str, img: Image.Image, blocks: list, orig: np.ndarray) -> None:
    arr = np.asarray(img.convert("L")).astype(np.int16)
    print(f"\n--- {name}: image {img.size} ---")
    if img.size[0] < 8:
        print("    !!! 1x1 placeholder (no real render)")
        return
    sizes = []
    for b in blocks:
        x1, y1, x2, y2 = (int(v) for v in (b["minX"], b["minY"], b["maxX"], b["maxY"]))
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(arr.shape[1], x2), min(arr.shape[0], y2)
        if x2 - x1 < 6 or y2 - y1 < 6:
            continue
        diff = np.abs(arr[y1:y2, x1:x2] - orig[y1:y2, x1:x2]) > 50
        jp = glyph_h(orig[y1:y2, x1:x2] < 128, 5, 200)
        cn = glyph_h(diff)
        sizes.append((x2 - x1, y2 - y1, jp, cn))
    xs = [s[3] / s[2] for s in sizes if s[2]]
    print(f"    boxes={len(sizes)}  rendered/orig glyph ratio: "
          f"median={np.median(xs):.2f}  max={max(xs):.2f}" if xs else "    no measurable boxes")
    for s in sizes:
        print(f"      box {s[0]:>4}x{s[1]:<4} JP={s[2]:>3}px CN={s[3]:>3}px ratio={(s[3]/s[2] if s[2] else 0):.2f}")


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    orig = np.asarray(Image.open(PAGE).convert("L")).astype(np.int16)

    img, blocks = page_call(APP_CFG)
    img.save(OUT / "step1.app.png")
    report("STEP 1  non-streaming, App config", img, blocks, orig)

    img2, blocks2 = page_call(WEB_CFG)
    img2.save(OUT / "step2.web.png")
    report("STEP 2  non-streaming, Web-UI config", img2, blocks2, orig)

    code, nbytes = stream_web_call(WEB_CFG)
    print(f"\nSTEP 3  web-UI streaming call: HTTP {code}, {nbytes} bytes")

    img3, blocks3 = page_call(APP_CFG)
    img3.save(OUT / "step4.app_after_web.png")
    report("STEP 4  non-streaming, App config AFTER a web-UI request", img3, blocks3, orig)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
