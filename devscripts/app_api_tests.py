"""End-to-end bug hunt for the App API (mit-app-api, port 8020).

Order matters: clean-engine tests first, the cross-contamination test last.

Run:  python devscripts/app_api_tests.py [--base http://127.0.0.1:8020]
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests
from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

ORIG = Path("epub_test/orig")
REPORT: list[dict] = []
RESULTS_OUT = Path("epub_test/api_test_report.json")


def rec(tid: str, name: str, ok: bool, detail: str = "", **extra) -> None:
    tag = "PASS" if ok else "FAIL"
    REPORT.append({"id": tid, "name": name, "ok": ok, "detail": detail, **extra})
    print(f"[{tag}] {tid} {name}  {detail}", flush=True)


def catalog() -> dict[int, str]:
    return {p["index"]: p["file"] for p in
            json.loads((ORIG / "pages.json").read_text(encoding="utf-8"))["pages"]}


def translate(base: str, page: int, cat: dict[int, str], **form) -> requests.Response:
    data = {k: v for k, v in form.items() if v is not None}
    with open(ORIG / cat[page], "rb") as fh:
        return requests.post(f"{base}/v1/pages/translate", files={"image": (cat[page], fh, "image/jpeg")},
                             data=data, timeout=900)


def img_size(base: str, page_id: int, orig: bool = False) -> tuple[int, int] | None:
    r = requests.get(f"{base}/v1/pages/{page_id}/image", params={"orig": 1} if orig else {}, timeout=180)
    if r.status_code != 200:
        return None
    return Image.open(io.BytesIO(r.content)).size


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8020")
    args = ap.parse_args()
    base = args.base
    cat = catalog()
    tag = time.strftime("%H%M%S")
    book = f"apitest-{tag}"
    ids: dict[str, int] = {}

    # ---------- T1 health / T2 capabilities ----------
    try:
        r = requests.get(f"{base}/v1/health", timeout=20)
        j = r.json()
        ok = r.status_code == 200 and j.get("status") == "ok" and j["engine"]["ok"] and j["db"]["ok"] and j["redis"]["ok"]
        rec("T1", "GET /v1/health", ok, json.dumps(j, ensure_ascii=False))
    except Exception as e:  # noqa: BLE001
        rec("T1", "GET /v1/health", False, f"{type(e).__name__}: {e}")

    try:
        r = requests.get(f"{base}/v1/capabilities", timeout=20)
        j = r.json()
        rec("T2", "GET /v1/capabilities", r.status_code == 200 and "default_config" in j and "endpoints" in j,
            f"default_config={json.dumps(j.get('default_config'), ensure_ascii=False)}")
    except Exception as e:  # noqa: BLE001
        rec("T2", "GET /v1/capabilities", False, f"{type(e).__name__}: {e}")

    # ---------- T3 fresh translate ----------
    t0 = time.time()
    try:
        r = translate(base, 20, cat, book_id=book, page_index=1, title="API 测试本", order_dir="rtl")
        j = r.json()
        pid = j.get("page_id")
        ids["fresh"] = pid
        size = img_size(base, pid) if pid else None
        orig_size = Image.open(ORIG / cat[20]).size
        ok = (r.status_code == 200 and j.get("status") == "done" and not j.get("cached")
              and size == orig_size and len(j.get("blocks", [])) > 0)
        rec("T3", "首次翻译（整条管线）", bool(ok),
            f"HTTP {r.status_code} page_id={pid} cached={j.get('cached')} blocks={len(j.get('blocks', []))} "
            f"image={size} orig={orig_size} elapsed={j.get('elapsed_ms')}ms wall={time.time()-t0:.1f}s")
    except Exception as e:  # noqa: BLE001
        rec("T3", "首次翻译（整条管线）", False, f"{type(e).__name__}: {e}")

    # ---------- T4 cache hit ----------
    pid0 = ids.get("fresh")
    if pid0:
        try:
            t0 = time.time()
            r = translate(base, 20, cat, book_id=book, page_index=1)
            j = r.json()
            size = img_size(base, j.get("page_id"))
            ok = (r.status_code == 200 and j.get("cached") is True and j.get("elapsed_ms", 9999) < 2000
                  and size == Image.open(ORIG / cat[20]).size)
            rec("T4", "L1 缓存命中", bool(ok),
                f"cached={j.get('cached')} elapsed={j.get('elapsed_ms')}ms wall={time.time()-t0:.2f}s image={size}")
        except Exception as e:  # noqa: BLE001
            rec("T4", "L1 缓存命中", False, f"{type(e).__name__}: {e}")

    # ---------- T5 structured json ----------
    if pid0:
        try:
            r = requests.get(f"{base}/v1/pages/{pid0}/json", timeout=60)
            j = r.json()
            blocks = j if isinstance(j, list) else j.get("blocks", [])
            with_src = [b for b in blocks if b.get("src_text")]
            ok = r.status_code == 200 and len(blocks) > 0 and len(with_src) > 0
            rec("T5", "GET /v1/pages/{id}/json 结构化结果", bool(ok),
                f"HTTP {r.status_code} blocks={len(blocks)} 含原文={len(with_src)} "
                f"样例={ [(b.get('src_text') or '')[:12] for b in blocks[:2]] }")
        except Exception as e:  # noqa: BLE001
            rec("T5", "GET /v1/pages/{id}/json 结构化结果", False, f"{type(e).__name__}: {e}")

    # ---------- T6 ad-hoc bucket (no book_id) ----------
    try:
        r = translate(base, 40, cat)
        j = r.json()
        size = img_size(base, j["page_id"])
        ok = r.status_code == 200 and size == Image.open(ORIG / cat[40]).size
        rec("T6", "不传 book_id（_adhoc 桶）", bool(ok), f"HTTP {r.status_code} page_id={j.get('page_id')} image={size}")
    except Exception as e:  # noqa: BLE001
        rec("T6", "不传 book_id（_adhoc 桶）", False, f"{type(e).__name__}: {e}")

    # ---------- T7 client-supplied context ----------
    try:
        ctx = json.dumps([{"src": "ニャー", "dst": "喵"}], ensure_ascii=False)
        r = translate(base, 50, cat, book_id=book, page_index=5, context=ctx)
        j = r.json()
        rec("T7", "客户端 context 注入", r.status_code == 200 and j.get("context_used") == "client",
            f"HTTP {r.status_code} context_used={j.get('context_used')}")
    except Exception as e:  # noqa: BLE001
        rec("T7", "客户端 context 注入", False, f"{type(e).__name__}: {e}")

    # ---------- T8 db context (previous pages of same book) ----------
    try:
        r = translate(base, 70, cat, book_id=book, page_index=50)
        j = r.json()
        rec("T8", "服务端按 book_id 取上文", r.status_code == 200 and j.get("context_used") == "db",
            f"HTTP {r.status_code} context_used={j.get('context_used')}")
    except Exception as e:  # noqa: BLE001
        rec("T8", "服务端按 book_id 取上文", False, f"{type(e).__name__}: {e}")

    # ---------- T9 retranslate with a different translator ----------
    try:
        r = translate(base, 80, cat, book_id=book, page_index=80)
        j = r.json()
        pid = j["page_id"]
        ids["retrans"] = pid
        before = [b.get("dst") for b in j.get("blocks", [])][:1]
        r2 = requests.post(f"{base}/v1/pages/{pid}/retranslate", timeout=900,
                           json={"config": {"translator": {"translator": "none"}},
                                 "force": True})
        j2 = r2.json()
        blocks2 = j2.get("blocks", [])
        after = [b.get("dst") for b in blocks2][:1]
        size2 = img_size(base, pid)
        ok = r2.status_code == 200 and len(blocks2) > 0 and all((b.get("dst") or "") == "" for b in blocks2)
        rec("T9", "POST /retranslate 换翻译器重译", bool(ok),
            f"HTTP {r2.status_code} 前={before} 后={after} blocks={len(blocks2)} image={size2}")
    except Exception as e:  # noqa: BLE001
        rec("T9", "POST /retranslate 换翻译器重译", False, f"{type(e).__name__}: {e}")

    # ---------- T10 books endpoints ----------
    try:
        r1 = requests.post(f"{base}/v1/books", json={"id": book, "title": "API 测试本", "order_dir": "rtl"}, timeout=30)
        r2 = requests.get(f"{base}/v1/books", timeout=30)
        r3 = requests.get(f"{base}/v1/books/{book}/pages", timeout=30)
        books = r2.json()
        books = books if isinstance(books, list) else books.get("books", [])
        pages = r3.json()
        pages = pages if isinstance(pages, list) else pages.get("pages", [])
        ok = (r1.status_code == 200 and r2.status_code == 200 and r3.status_code == 200
              and any(str(b.get("id")) == book for b in books) and len(pages) > 0)
        rec("T10", "books CRUD/列表/页列表", bool(ok),
            f"POST={r1.status_code} LIST={len(books)} PAGES={len(pages)}")
    except Exception as e:  # noqa: BLE001
        rec("T10", "books CRUD/列表/页列表", False, f"{type(e).__name__}: {e}")

    # ---------- T11 error handling ----------
    cases = []
    # empty image
    r = requests.post(f"{base}/v1/pages/translate", files={"image": ("e.png", b"", "image/png")}, timeout=60)
    cases.append(("空图片 -> 400", r.status_code == 400, r.status_code))
    # bad config json
    with open(ORIG / cat[15], "rb") as fh:
        r = requests.post(f"{base}/v1/pages/translate", files={"image": (cat[15], fh, "image/jpeg")},
                          data={"book_id": book, "page_index": 15, "config": "{not json"}, timeout=60)
    cases.append(("坏 config JSON -> 400", r.status_code == 400, r.status_code))
    with open(ORIG / cat[15], "rb") as fh:
        r = requests.post(f"{base}/v1/pages/translate", files={"image": (cat[15], fh, "image/jpeg")},
                          data={"book_id": book, "page_index": 15, "context": "[oops"}, timeout=60)
    cases.append(("坏 context JSON -> 400", r.status_code == 400, r.status_code))
    r = requests.get(f"{base}/v1/pages/99999999/json", timeout=30)
    cases.append(("不存在 page_id json -> 404", r.status_code == 404, r.status_code))
    r = requests.get(f"{base}/v1/pages/99999999/image", timeout=30)
    cases.append(("不存在 page_id image -> 404", r.status_code == 404, r.status_code))
    with open(ORIG / cat[15], "rb") as fh:
        r = requests.post(f"{base}/v1/pages/translate", files={"image": (cat[15], fh, "image/jpeg")},
                          data={"page_index": "abc"}, timeout=60)
    cases.append(("非法 page_index -> 4xx", 400 <= r.status_code < 500, r.status_code))
    ok = all(c[1] for c in cases)
    rec("T11", "错误处理矩阵", ok, "; ".join(f"{n}={s}" for n, o, s in cases))

    # ---------- T12 无文字页（引擎已知崩溃） ----------
    try:
        r = translate(base, 5, cat, book_id=book, page_index=5)
        detail = r.text[:160]
        rec("T12", "无文字页（page 005）", r.status_code == 200,
            f"HTTP {r.status_code} {detail}", note="引擎 text_regions=None 时崩溃；期望至少不是 5xx")
    except Exception as e:  # noqa: BLE001
        rec("T12", "无文字页（page 005）", False, f"{type(e).__name__}: {e}")

    # ---------- T13 并发：同书不同页 ----------
    try:
        def one(pg: int):
            r = translate(base, pg, cat, book_id=f"{book}-cc", page_index=pg)
            j = r.json()
            return pg, r.status_code, j.get("page_id"), len(j.get("blocks", [])), j.get("cached")

        with ThreadPoolExecutor(max_workers=3) as ex:
            res = list(ex.map(one, [100, 120, 140]))
        sizes = [img_size(base, r[2]) if r[2] else None for r in res]
        ok = all(r[1] == 200 and s == Image.open(ORIG / cat[r[0]]).size for r, s in zip(res, sizes))
        rec("T13", "并发 3 页（同书）", bool(ok), f"{res} images={sizes}")
    except Exception as e:  # noqa: BLE001
        rec("T13", "并发 3 页（同书）", False, f"{type(e).__name__}: {e}")

    # ---------- T14 同一页并发（缓存竞态） ----------
    try:
        def same(_):
            r = translate(base, 160, cat, book_id=f"{book}-race", page_index=1)
            j = r.json()
            return r.status_code, j.get("page_id"), j.get("cached"), len(j.get("blocks", []))

        with ThreadPoolExecutor(max_workers=3) as ex:
            res = list(ex.map(same, range(3)))
        pids = {r[1] for r in res}
        sizes = [img_size(base, r[1]) for r in res if r[1]]
        ok = all(r[0] == 200 for r in res) and len(pids) == 1 and all(s == Image.open(ORIG / cat[160]).size for s in sizes)
        rec("T14", "同一页并发 3 次（竞态/重复页）", bool(ok), f"{res} distinct_page_ids={len(pids)} images={sizes}")
    except Exception as e:  # noqa: BLE001
        rec("T14", "同一页并发 3 次（竞态/重复页）", False, f"{type(e).__name__}: {e}")

    # ---------- T15 跨端污染：网页端用一次后，App 接口会不会拿到 1x1 ----------
    try:
        with open(ORIG / cat[190], "rb") as fh:
            rw = requests.post("http://127.0.0.1:8010/translate/with-form/image/stream/web",
                               files={"image": (cat[190], fh, "image/jpeg")},
                               data={"config": json.dumps({"translator": {"translator": "none"}})}, timeout=900)
        r = translate(base, 180, cat, book_id=f"{book}-poison", page_index=1)
        j = r.json()
        pid = j.get("page_id")
        size = img_size(base, pid) if pid else None
        expected = Image.open(ORIG / cat[180]).size
        ok = size == expected
        rec("T15", "网页端用过之后，App 接口仍返回真图", bool(ok),
            f"web-stream HTTP {rw.status_code}; app page_id={pid} image={size} expected={expected}",
            note="1x1 说明引擎 _is_streaming_mode 粘滞状态污染了非流式调用")
    except Exception as e:  # noqa: BLE001
        rec("T15", "网页端用过之后，App 接口仍返回真图", False, f"{type(e).__name__}: {e}")

    RESULTS_OUT.write_text(json.dumps(REPORT, ensure_ascii=False, indent=2), encoding="utf-8")
    bad = [r for r in REPORT if not r["ok"]]
    print(f"\n==== {len(REPORT) - len(bad)}/{len(REPORT)} passed, {len(bad)} failed -> {RESULTS_OUT}", flush=True)
    for b in bad:
        print(f"   FAIL {b['id']} {b['name']}: {b['detail']}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
