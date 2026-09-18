"""硬指标 QA：本地 / 免费 / 毫秒级的渲染质量测量。

对一页（原图 + 译文图 + 块级 bbox）量 5 项，用于决定"要不要重译 / 调什么参数"：

  1. 字号比：渲染笔画高度 / 原文字号（连通域中位高度，>1.3 通常偏大）
  2. 面积比：渲染文字包围盒面积 / 原框面积（>1.5 说明字塞不下被撑大了）
  3. 溢出原框：新画笔画落在「所有原框并集」之外的比例
  4. 越过图片边界：新画笔画落在图片四边之外（这里恒 0，保留给裁剪版）
  5. 跨块重叠：原框互不相交、但渲染后文字框相交的块对数量（文字盖文字的直接证据）

用法：
    python devscripts/qa_metrics.py --orig epub_test/orig/page-059.jpg \
        --out epub_test/fontfit/xx.png --blocks epub_test/out/results.json --page 59
    python devscripts/qa_metrics.py --page-id 87 --base http://127.0.0.1:8020   # 直接查线上页
"""
from __future__ import annotations

import argparse
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


def comp_h(mask: np.ndarray, lo=5, hi=400) -> int:
    lab, n = ndimage.label(mask)
    hs = []
    for sl in ndimage.find_objects(lab) if n else []:
        h = sl[0].stop - sl[0].start
        w = sl[1].stop - sl[1].start
        if lo <= h <= hi and 2 <= w <= hi:
            hs.append(h)
    return int(np.median(hs)) if hs else 0


def ink_bbox(mask: np.ndarray) -> tuple | None:
    ys, xs = np.where(mask)
    if not len(xs):
        return None
    return (int(xs.min()), int(ys.min()), int(xs.max() + 1), int(ys.max() + 1))


def metrics(orig: np.ndarray, out: np.ndarray, boxes: list[tuple[int, int, int, int]]) -> dict:
    oa = orig.astype(np.int16)
    ba = out.astype(np.int16)
    new_ink = (ba < 25) & (oa > 200)                      # 新画的黑字（抹字只会变白，不误判）
    inside = np.zeros_like(new_ink)
    per: list[dict] = []
    for i, (x1, y1, x2, y2) in enumerate(boxes):
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(ba.shape[1], x2), min(ba.shape[0], y2)
        if x2 - x1 < 6 or y2 - y1 < 6:
            continue
        inside[y1:y2, x1:x2] = True
        jp = comp_h(oa[y1:y2, x1:x2] < 128, 5, 200)
        cn = comp_h(new_ink[y1:y2, x1:x2])
        ib = ink_bbox(new_ink[y1:y2, x1:x2])
        area_box = (x2 - x1) * (y2 - y1)
        area_ink = (ib[2] - ib[0]) * (ib[3] - ib[1]) if ib else 0
        per.append({
            "i": i, "bbox": [x1, y1, x2, y2],
            "jp_px": jp, "cn_px": cn, "font_ratio": round(cn / jp, 2) if jp else 0,
            "area_ratio": round(area_ink / area_box, 2) if area_box else 0,
        })

    overflow = float((new_ink & ~inside).sum()) / max(1, int(new_ink.sum()))
    full = ink_bbox(new_ink)
    crosses_edge = bool(full and (full[0] <= 0 or full[1] <= 0 or full[2] >= ba.shape[1] or full[3] >= ba.shape[0]))

    # 跨块重叠：原框不相交，但渲染文字包围盒相交
    pairs = 0
    for a in range(len(per)):
        for b in range(a + 1, len(per)):
            A, B = per[a], per[b]
            ax1, ay1, ax2, ay2 = A["bbox"]
            bx1, by1, bx2, by2 = B["bbox"]
            if ax2 <= bx1 or bx2 <= ax1 or ay2 <= by1 or by2 <= ay1:     # 原框不相交
                ia = ink_bbox(new_ink[ay1:ay2, ax1:ax2])
                ib = ink_bbox(new_ink[by1:by2, bx1:bx2])
                if ia and ib:
                    ia = (ia[0] + ax1, ia[1] + ay1, ia[2] + ax1, ia[3] + ay1)
                    ib = (ib[0] + bx1, ib[1] + by1, ib[2] + bx1, ib[3] + by1)
                    if not (ia[2] <= ib[0] or ib[2] <= ia[0] or ia[3] <= ib[1] or ib[3] <= ia[1]):
                        pairs += 1
    return {
        "blocks": per,
        "font_ratio_median": round(float(np.median([p["font_ratio"] for p in per])), 2) if per else 0,
        "font_ratio_max": round(float(max(p["font_ratio"] for p in per)), 2) if per else 0,
        "overflow_outside_bbox": round(overflow, 3),
        "crosses_image_edge": crosses_edge,
        "overlapping_pairs": pairs,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--orig", default="")
    ap.add_argument("--out", default="")
    ap.add_argument("--blocks", default="", help="results.json（含 blocks）或直接给 bbox json")
    ap.add_argument("--page", type=int, default=None)
    ap.add_argument("--page-id", type=int, default=None, help="从线上 app-api 取 orig/out/结果")
    ap.add_argument("--base", default="http://127.0.0.1:8020")
    a = ap.parse_args()

    if a.page_id:
        base = a.base
        j = requests.get(f"{base}/v1/pages/{a.page_id}/json", timeout=30).json()
        orig = requests.get(f"{base}/v1/pages/{a.page_id}/image", params={"orig": 1}, timeout=120).content
        out = requests.get(f"{base}/v1/pages/{a.page_id}/image", timeout=120).content
        oa = np.asarray(Image.open(io.BytesIO(orig)).convert("L"))
        ba = np.asarray(Image.open(io.BytesIO(out)).convert("L"))
        boxes = [(b["minx"], b["miny"], b["maxx"], b["maxy"]) for b in j.get("blocks", [])]
    else:
        oa = np.asarray(Image.open(a.orig).convert("L"))
        ba = np.asarray(Image.open(a.out).convert("L"))
        if a.blocks:
            data = json.loads(Path(a.blocks).read_text(encoding="utf-8"))
            if "pages" in data and a.page is not None:
                page = next(p for p in data["pages"] if p["index"] == a.page)
                boxes = [(int(v) for v in b["bbox"]) for b in page["blocks"]]
            elif isinstance(data, list):
                boxes = [(int(v) for v in b["bbox"]) for b in data]
            else:
                boxes = [(int(v) for v in b["bbox"]) for b in data["blocks"]]
        else:
            boxes = []

    if ba.shape != oa.shape:
        ba = np.asarray(Image.fromarray(ba.astype(np.uint8)).resize((oa.shape[1], oa.shape[0])))
    m = metrics(oa, ba, boxes)
    print(f"块数={len(m['blocks'])}  字号比 中位={m['font_ratio_median']} 最大={m['font_ratio_max']}  "
          f"溢出原框={m['overflow_outside_bbox']:.1%}  越界={m['crosses_image_edge']}  跨块重叠={m['overlapping_pairs']} 对")
    for p in sorted(m["blocks"], key=lambda x: -x["font_ratio"])[:10]:
        print(f"  block{p['i']:>2} bbox={p['bbox']} 原字号={p['jp_px']:>3}px 渲字号={p['cn_px']:>3}px "
              f"字号比={p['font_ratio']:<4} 面积比={p['area_ratio']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
