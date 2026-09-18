"""译文长度 vs 渲染字号：到底会不会"按长度缩字/换列"？

挑一页「日文短、中文长」最明显的页，用 4 种配置各跑一次，逐块量：
  * 渲染字号 / 原文字号（新出现的黑色笔画 vs 原图笔画，连通域中位高度）
  * 溢出：新笔画里落在「所有原文字块 bbox 之外」的比例（框被撑大/超出原位的程度）

用法: python devscripts/font_fit_test.py [page_index]
"""
from __future__ import annotations

import base64
import io
import json
import sys
from pathlib import Path

import numpy as np
import requests
from PIL import Image
from scipy import ndimage

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

ORIG = Path("epub_test/orig")
OUT = Path("epub_test/fontfit")
ENGINE = "http://127.0.0.1:8010/translate/with-form/page"

BASE = {"translator": {"translator": "deepseek", "target_lang": "CHS"},
        "detector": {"detector": "ctd", "detection_size": 2048},
        "ocr": {"ocr": "mocr", "use_mocr_merge": True},
        "inpainter": {"inpainter": "lama_large", "inpainting_size": 1024, "inpainting_precision": "bf16"},
        "render": {"renderer": "default", "font_color": "000000"}}

VARIANTS = {
    "A 现状(renderer=default)": {},
    "B 字号 -8": {"render": {"font_size_offset": -8}},
    "C1 manga2eng(中文已放开)": {"render": {"renderer": "manga2eng"}},
    "C2 manga2eng_pillow(中文已放开)": {"render": {"renderer": "manga2eng_pillow"}},
    "D 网页端配置": {"detector": {"detector": "default", "detection_size": 1536, "box_threshold": 0.7},
                     "ocr": {"ocr": "48px"}, "render": {"renderer": "default"}},
}

HALF = set(" .,:;!?'\"-()[]{}")


def text_len(s: str) -> float:
    return sum(0.5 if ch in HALF else 1.0 for ch in s.strip())


def merge(a: dict, b: dict) -> dict:
    out = json.loads(json.dumps(a))
    for k, v in b.items():
        out[k] = merge(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) else v
    return out


def comp_h(mask: np.ndarray, lo=5, hi=400) -> int:
    lab, n = ndimage.label(mask)
    hs = []
    for sl in ndimage.find_objects(lab) if n else []:
        h = sl[0].stop - sl[0].start
        w = sl[1].stop - sl[1].start
        if lo <= h <= hi and 2 <= w <= hi:
            hs.append(h)
    return int(np.median(hs)) if hs else 0


def pick_page() -> int:
    data = json.loads(Path("epub_test/out/results.json").read_text(encoding="utf-8"))
    best, score = None, 0.0
    for p in data["pages"]:
        s = sum(1 for b in p.get("blocks", [])
                if b.get("src") and b.get("dst") and text_len(b["dst"]) >= 1.6 * text_len(b["src"]))
        if s > score:
            best, score = p["index"], s
    return best or 100


def run(page: int, cfg: dict) -> tuple[Image.Image, list]:
    img = (ORIG / f"page-{page:03d}.jpg").read_bytes()
    r = requests.post(ENGINE, files={"image": (f"page-{page:03d}.jpg", img, "image/jpeg")},
                      data={"config": json.dumps(cfg, ensure_ascii=False)}, timeout=900)
    r.raise_for_status()
    j = r.json()
    return Image.open(io.BytesIO(base64.b64decode(j["image_b64"]))).convert("RGB"), j["result"]["translations"]


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    page = int(sys.argv[1]) if len(sys.argv) > 1 else pick_page()
    orig = Image.open(ORIG / f"page-{page:03d}.jpg").convert("L")
    oa = np.asarray(orig).astype(np.int16)
    print(f"取样页: page-{page:03d}  (原图 {orig.size})\n")

    for i, (name, over) in enumerate(VARIANTS.items()):
        img, blocks = run(page, merge(BASE, over))
        img.save(OUT / f"page-{page:03d}.{i}-{name.split()[0]}.png")   # 用序号保证不互相覆盖
        ba = np.asarray(img.convert("L")).astype(np.int16)
        new_ink = (ba < 40) & (oa > 120)                    # 新画的黑色笔画（抹字是变白，不会误判）
        inside = np.zeros_like(new_ink)
        boxes = []
        for b in blocks:
            x1, y1, x2, y2 = (int(v) for v in (b["minX"], b["minY"], b["maxX"], b["maxY"]))
            boxes.append((x1, y1, x2, y2))
            x1c, y1c = max(0, x1), max(0, y1)
            x2c, y2c = min(new_ink.shape[1], x2), min(new_ink.shape[0], y2)
            if x2c > x1c and y2c > y1c:
                inside[y1c:y2c, x1c:x2c] = True
        ov = int((new_ink & ~inside).sum())
        tot = int(new_ink.sum()) or 1

        ratios, lines = [], []
        for b in blocks:
            x1, y1, x2, y2 = (int(v) for v in (b["minX"], b["minY"], b["maxX"], b["maxY"]))
            x1, y1 = max(0, x1), max(0, y1)
            x2, y2 = min(oa.shape[1], x2), min(oa.shape[0], y2)
            if x2 - x1 < 6 or y2 - y1 < 6:
                continue
            jp = comp_h(oa[y1:y2, x1:x2] < 128, 5, 200)
            cn = comp_h(new_ink[y1:y2, x1:x2])
            if jp:
                txt = b.get("text") or {}
                src, dst = txt.get("ja", ""), txt.get("CHS", "") or txt.get("ENG", "")
                ratios.append(cn / jp)
                lines.append((src, dst, text_len(src), text_len(dst), jp, cn, round(cn / jp, 2)))
        med = float(np.median(ratios)) if ratios else 0
        print(f"[{name}]  块数={len(blocks)}  渲染/原文字号 中位={med:.2f} 最大={max(ratios) if ratios else 0:.2f}"
              f"  溢出笔画占比={ov / tot:.1%}")
        for s, d, ls, ld, jp, cn, r in sorted(lines, key=lambda t: -t[6])[:4]:
            print(f"      原{ls:>4.1f}字→译{ld:>4.1f}字  JP={jp:>3}px CN={cn:>3}px x{r:<5} {s} => {d}")
        print(flush=True)
    print(f"（对比图已存 {OUT}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
