"""译文长度 与 渲染字号 的关系（用抹字底图做差值，精确抠出渲染出来的字）

关键点：include_background 返回每块的**抹字底图**，用 (译文图 - 底图) 的差异
就能只拿到"渲染上去的文字"，不受 inpainting 涂抹干扰。

输出：逐块的  (译文/原文 字数比)  ×  (渲染字号/原文字号)  ×  (是否顶到框边)
      以及二者的相关系数 —— 若真是"按长度缩字"，相关系数应为负、长译文应更小。

用法: python devscripts/font_fit_test2.py [page1,page2,...]
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
CFG = {"translator": {"translator": "deepseek", "target_lang": "CHS"},
       "detector": {"detector": "ctd", "detection_size": 2048},
       "ocr": {"ocr": "mocr", "use_mocr_merge": True},
       "inpainter": {"inpainter": "lama_large", "inpainting_size": 1024, "inpainting_precision": "bf16"},
       "render": {"renderer": "default", "font_color": "000000"}}

HALF = set(" .,:;!?'\"-()[]{}")


def text_len(s: str) -> float:
    return sum(0.5 if ch in HALF else 1.0 for ch in (s or "").strip())


def comp_h(mask: np.ndarray, lo=5, hi=400) -> int:
    lab, n = ndimage.label(mask)
    hs = []
    for sl in ndimage.find_objects(lab) if n else []:
        h = sl[0].stop - sl[0].start
        w = sl[1].stop - sl[1].start
        if lo <= h <= hi and 2 <= w <= hi:
            hs.append(h)
    return int(np.median(hs)) if hs else 0


def run_page(page: int) -> list[dict]:
    img = (ORIG / f"page-{page:03d}.jpg").read_bytes()
    r = requests.post(ENGINE, files={"image": (f"page-{page:03d}.jpg", img, "image/jpeg")},
                      data={"config": json.dumps(CFG, ensure_ascii=False)}, timeout=900)
    r.raise_for_status()
    j = r.json()
    out = Image.open(io.BytesIO(base64.b64decode(j["image_b64"]))).convert("L")
    out.save(OUT / f"page-{page:03d}.png")
    oa = np.asarray(Image.open(ORIG / f"page-{page:03d}.jpg").convert("L")).astype(np.int16)
    outa = np.asarray(out).astype(np.int16)
    # 干净的"新画上去的黑字"掩膜：成品近纯黑 且 原图那一处是近白（抹字只会变白，不会变黑）
    ink = (outa < 25) & (oa > 200)
    rows = []
    for b in j["result"]["translations"]:
        x1, y1, x2, y2 = int(b["minX"]), int(b["minY"]), int(b["maxX"]), int(b["maxY"])
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(ink.shape[1], x2), min(ink.shape[0], y2)
        if x2 - x1 < 6 or y2 - y1 < 6:
            continue
        mask = ink[y1:y2, x1:x2]
        if mask.sum() < 30:
            continue
        jp = comp_h(oa[y1:y2, x1:x2] < 128, 5, 200)
        cn = comp_h(mask)
        # 文字是否顶到框边（框装不下 / 被撑满）
        edge = 0
        lab, n = ndimage.label(mask)
        for sl in (ndimage.find_objects(lab) if n else []):
            a, bb = sl[0], sl[1]
            if a.start <= 1 or bb.start <= 1 or a.stop >= mask.shape[0] - 1 or bb.stop >= mask.shape[1] - 1:
                edge += 1
        txt = b.get("text") or {}
        src, dst = txt.get("ja", ""), txt.get("CHS", "")
        rows.append({"page": page, "src": src, "dst": dst,
                     "ls": text_len(src), "ld": text_len(dst),
                     "box": (x2 - x1, y2 - y1), "jp_px": jp, "cn_px": cn,
                     "ratio": round(cn / jp, 2) if jp else 0,
                     "edge_hits": edge})
    return rows


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    pages = [int(x) for x in sys.argv[1].split(",")] if len(sys.argv) > 1 else [100, 130, 160]
    rows: list[dict] = []
    for p in pages:
        try:
            rows += run_page(p)
            print(f"page-{p:03d}: 采到 {sum(1 for r in rows if r['page'] == p)} 块", flush=True)
        except Exception as e:  # noqa: BLE001
            print(f"page-{p:03d} 失败: {type(e).__name__}: {e}", flush=True)

    print(f"\n{'页':>4} {'原/译字数':>9} {'膨胀':>5} {'框 w×h':>9} {'JP':>4} {'CN':>4} {'CN/JP':>6} {'顶边':>4}  原文 => 译文")
    for r in sorted(rows, key=lambda r: -(r["ld"] / max(0.5, r["ls"]))):
        exp = r["ld"] / max(0.5, r["ls"])
        print(f"{r['page']:>4} {r['ls']:>4.1f}/{r['ld']:<4.1f} {exp:>5.2f} "
              f"{r['box'][0]:>4}x{r['box'][1]:<4} {r['jp_px']:>4} {r['cn_px']:>4} {r['ratio']:>6} {r['edge_hits']:>4}  "
              f"{r['src'][:16]} => {r['dst'][:16]}")

    ok = [r for r in rows if r["jp_px"] and r["ls"]]
    if len(ok) >= 5:
        exp = np.array([r["ld"] / max(0.5, r["ls"]) for r in ok])
        rat = np.array([r["ratio"] for r in ok])
        print(f"\n样本 {len(ok)} 块；「译文膨胀倍数」与「渲染字号/原文字号」的皮尔逊相关系数 r = {np.corrcoef(exp, rat)[0, 1]:+.2f}")
        print("（按长度自适应缩字的话，r 应为负；靠撑框+放大字则会是非负）")
        for lo, hi in [(0, 1.0), (1.0, 1.5), (1.5, 2.5), (2.5, 99)]:
            sel = [r for r in ok if lo <= r["ld"] / max(0.5, r["ls"]) < hi]
            if sel:
                print(f"  膨胀 {lo}~{hi if hi < 99 else '∞'}: {len(sel):>2} 块  "
                      f"字号比中位={np.median([r['ratio'] for r in sel]):.2f}  "
                      f"顶边块数={sum(1 for r in sel if r['edge_hits'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
