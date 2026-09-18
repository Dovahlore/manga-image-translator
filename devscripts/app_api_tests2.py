"""App API round 2 — tests that need a real pipeline run (fresh pages / force).

phase 1 : cross-page context correctness + the cache-hit context hole + config
          deep-merge (target_lang) + book metadata + include_background
phase 2 : poison the engine through the web endpoint, then show what the App
          API does (force=true bypasses the App's L1 cache so the engine is hit)
phase 3 : after the engine is restarted, is the poisoned App cache entry sticky?
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import time
from pathlib import Path

import requests
from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

ORIG = Path("epub_test/orig")
ENGINE = "http://127.0.0.1:8010"
REPORT: list[dict] = []
OUT_JSON = Path("epub_test/api_test_report2.json")


def rec(tid: str, name: str, ok: bool, detail: str = "") -> None:
    REPORT.append({"id": tid, "name": name, "ok": ok, "detail": detail})
    print(f"[{'PASS' if ok else 'FAIL'}] {tid} {name}  {detail}", flush=True)


def cat() -> dict[int, str]:
    return {p["index"]: p["file"] for p in
            json.loads((ORIG / "pages.json").read_text(encoding="utf-8"))["pages"]}


def call(base: str, page: int, c: dict, **form) -> tuple[requests.Response, dict, tuple | None]:
    data = {k: v for k, v in form.items() if v is not None}
    with open(ORIG / c[page], "rb") as fh:
        r = requests.post(f"{base}/v1/pages/translate", files={"image": (c[page], fh, "image/jpeg")},
                          data=data, timeout=900)
    try:
        j = r.json()
    except ValueError:
        return r, {}, None
    size = None
    if r.status_code == 200 and j.get("page_id"):
        rr = requests.get(f"{base}/v1/pages/{j['page_id']}/image", timeout=180)
        if rr.status_code == 200:
            size = Image.open(io.BytesIO(rr.content)).size
    return r, j, size


def phase1(base: str) -> None:
    c = cat()
    tag = time.strftime("%H%M%S")
    osize = lambda p: Image.open(ORIG / c[p]).size

    # ---- A: 真正跑管线时的跨页上下文 ----
    r, j, s = call(base, 11, c, book_id=f"CB1-{tag}", page_index=1)
    rec("R2-A1", "首翻（管线真跑）", r.status_code == 200 and j.get("cached") is False and s == osize(11),
        f"HTTP {r.status_code} cached={j.get('cached')} ctx={j.get('context_used')} blocks={len(j.get('blocks', []))} image={s}")
    r, j, s = call(base, 12, c, book_id=f"CB1-{tag}", page_index=2)
    rec("R2-A2", "第 2 页应带上文（ctx=db）", j.get("context_used") == "db",
        f"cached={j.get('cached')} ctx={j.get('context_used')} blocks={len(j.get('blocks', []))}")
    r, j, s = call(base, 13, c, book_id=f"CB1-{tag}", page_index=3)
    rec("R2-A3", "第 3 页应带上文（ctx=db）", j.get("context_used") == "db",
        f"cached={j.get('cached')} ctx={j.get('context_used')}")

    # ---- B: 缓存命中的页是否写入 page_context（A/B 对照）----
    r, j, s = call(base, 11, c, book_id=f"CB2-{tag}", page_index=1)
    first_cached = j.get("cached")
    r, j2, s = call(base, 12, c, book_id=f"CB2-{tag}", page_index=2)
    ctx2 = j2.get("context_used")
    rec("R2-B", "缓存命中的页也写入上下文", ctx2 == "db",
        f"CB2 第1页 cached={first_cached}（命中即未跑管线）→ 第2页 ctx={ctx2}"
        f" ；对照 CB1 第2页 ctx=db")

    # ---- C: config 深合并 / target_lang 覆盖 ----
    r, j, s = call(base, 25, c, book_id=f"CB3-{tag}", page_index=1,
                   config=json.dumps({"translator": {"target_lang": "ENG"}}))
    blocks = j.get("blocks", [])
    dst_l = [b.get("dst", "") for b in blocks if b.get("dst")]
    ascii_ratio = (sum(1 for d in dst_l if all(ord(ch) < 128 for ch in d)) / len(dst_l)) if dst_l else 0
    src_l = [b.get("src", "") for b in blocks if b.get("src")]
    jp_ratio = (sum(1 for d in src_l if any(ord(ch) > 0x3000 for ch in d)) / len(src_l)) if src_l else 0
    rec("R2-C", "target_lang=ENG 覆盖 + src/dst 标注正确", bool(dst_l) and ascii_ratio > 0.8 and jp_ratio > 0.8,
        f"blocks={len(blocks)} 纯ASCII译文占比={ascii_ratio:.2f} 含日文原文占比={jp_ratio:.2f} 例={dst_l[:1]}")

    # ---- D: 书籍元数据落库 ----
    book = f"CB4-{tag}"
    r, j, s = call(base, 35, c, book_id=book, page_index=3, title="测试本", order_dir="ltr")
    rb = requests.get(f"{base}/v1/books/{book}/pages", timeout=30)
    pages = rb.json()
    pages = pages if isinstance(pages, list) else pages.get("pages", [])
    row = next((p for p in pages if str(p.get("page_index")) == "3"), None)
    ok = row is not None and (row.get("order_dir") in ("ltr", None)) and row.get("status") == "done"
    rec("R2-D", "title/order_dir/page_index 落库", bool(ok), f"row={json.dumps(row, ensure_ascii=False, default=str)}")

    # ---- E: include_background ----
    r, j, s = call(base, 45, c, book_id=f"CB5-{tag}", page_index=1, include_background="true")
    b0 = (j.get("blocks") or [{}])[0]
    has_bg = bool(b0.get("background"))
    r2, j2, s2 = call(base, 45, c, book_id=f"CB5-{tag}", page_index=1, include_background="false")
    b0b = (j2.get("blocks") or [{}])[0]
    rec("R2-E", "include_background 生效", has_bg and "background" not in b0b,
        f"true: background={'有' if has_bg else '无'}（{len(b0.get('background') or '')} 字符）; false: "
        f"{'有' if b0b.get('background') else '无'}")


def phase2(base: str) -> None:
    c = cat()
    tag = time.strftime("%H%M%S")
    book = f"PB1-{tag}"
    # 1) 用网页端流式接口打一次引擎
    with open(ORIG / c[90], "rb") as fh:
        rw = requests.post(f"{ENGINE}/translate/with-form/image/stream/web",
                           files={"image": (c[90], fh, "image/jpeg")},
                           data={"config": json.dumps({"translator": {"translator": "none"}})}, timeout=900)
    print(f"[info] 网页端流式调用 HTTP {rw.status_code}", flush=True)

    # 2) App 接口 force=true（绕过 App 自己的缓存，直接打引擎）
    r, j, s = call(base, 26, c, book_id=book, page_index=1, force="true")
    expected = Image.open(ORIG / c[26]).size
    ok_force = s == expected
    rec("R2-F", "网页端用过之后，App force=true 仍出真图", bool(ok_force),
        f"HTTP {r.status_code} page_id={j.get('page_id')} image={s} expected={expected} "
        f"cache_key={j.get('cache_key')}")

    # 3) 再不带 force（走 App 缓存）
    r2, j2, s2 = call(base, 26, c, book_id=book, page_index=1)
    rec("R2-G", "污染图没有被写进 App 缓存", s2 == expected,
        f"cached={j2.get('cached')} image={s2} expected={expected}")
    if j2.get("cache_key"):
        ck = j2["cache_key"].replace(":", "_")
        print(f"[info] 缓存条目: D:\\mit-data\\cache\\result\\{j2['cache_key']}.png", flush=True)
    print(f"[info] 页产物: D:\\mit-data\\books\\{book}\\000001\\out.png", flush=True)


def phase3(base: str, book: str) -> None:
    c = cat()
    expected = Image.open(ORIG / c[26]).size
    r, j, s = call(base, 26, c, book_id=book, page_index=1)
    rec("R2-H", "引擎重启后，污染的 App 缓存是否仍粘住", s == expected,
        f"cached={j.get('cached')} image={s} expected={expected}")
    r, j, s = call(base, 26, c, book_id=book, page_index=1, force="true")
    rec("R2-I", "引擎重启后 force=true 恢复真图", s == expected,
        f"cached={j.get('cached')} image={s}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8020")
    ap.add_argument("--phase", default="1")
    ap.add_argument("--book", default="")
    a = ap.parse_args()
    if a.phase == "1":
        phase1(a.base)
    elif a.phase == "2":
        phase2(a.base)
    elif a.phase == "3":
        phase3(a.base, a.book)
    OUT_JSON.write_text(json.dumps(REPORT, ensure_ascii=False, indent=2), encoding="utf-8")
    bad = [r for r in REPORT if not r["ok"]]
    print(f"\n==== {len(REPORT) - len(bad)}/{len(REPORT)} passed, {len(bad)} failed", flush=True)
    for b in bad:
        print(f"   FAIL {b['id']} {b['name']}: {b['detail']}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
