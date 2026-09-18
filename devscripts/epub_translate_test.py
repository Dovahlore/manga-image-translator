"""Drive the App API over EPUB-extracted manga pages.

POST /v1/pages/translate per page (sequential so per-book context builds up),
save the translated page + structured blocks.

Usage:
    python devscripts/epub_translate_test.py --orig-dir epub_test/orig --out-dir epub_test/out \
        --book-id yani-neko-vol01 --title "ヤニねこ Vol.01" --pages 1,3,5,7
    python devscripts/epub_translate_test.py ... --all
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

import requests

# Windows 控制台默认 GBK，日文/中文输出会 UnicodeEncodeError
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass


def post_page(base: str, img: Path, book_id: str, title: str, page_index: int,
              config: str | None, force: bool, timeout: float) -> dict:
    files = {"image": (img.name, img.read_bytes(), "image/jpeg")}
    data = {"book_id": book_id, "title": title, "page_index": str(page_index), "order_dir": "rtl"}
    if config:
        data["config"] = config
    if force:
        data["force"] = "true"
    r = requests.post(f"{base}/v1/pages/translate", files=files, data=data, timeout=timeout)
    try:
        body = r.json()
    except ValueError:
        raise RuntimeError(f"HTTP {r.status_code}: {r.text[:400]}")
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}: {json.dumps(body, ensure_ascii=False)[:400]}")
    return body


def fetch_image(base: str, page_id: int, dest: Path, orig: bool = False, timeout: float = 120) -> int:
    url = f"{base}/v1/pages/{page_id}/image" + ("?orig=1" if orig else "")
    r = requests.get(url, timeout=timeout)
    r.raise_for_status()
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(r.content)
    return len(r.content)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:8020")
    ap.add_argument("--orig-dir", default="epub_test/orig")
    ap.add_argument("--out-dir", default="epub_test/out")
    ap.add_argument("--book-id", default="yani-neko-vol01")
    ap.add_argument("--title", default="ヤニねこ Vol.01")
    ap.add_argument("--pages", default="", help="comma list of page indices, e.g. 1,3,5")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--config", default=None, help="JSON overrides, e.g. {\"translator\":{\"translator\":\"sugoi\"}}")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--timeout", type=float, default=900.0)
    ap.add_argument("--save-orig", action="store_true", help="also copy originals into out-dir")
    args = ap.parse_args()

    orig_dir = Path(args.orig_dir)
    pages_json = json.loads((orig_dir / "pages.json").read_text(encoding="utf-8"))
    catalog = {p["index"]: p["file"] for p in pages_json["pages"]}

    if args.all:
        indices = sorted(catalog)
    elif args.pages:
        indices = [int(x) for x in args.pages.split(",") if x.strip()]
    else:
        indices = sorted(catalog)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    health = requests.get(f"{args.base_url}/v1/health", timeout=20).json()
    print(f"[health] {json.dumps(health, ensure_ascii=False)}", flush=True)

    records = []
    t_all = time.time()
    for i, idx in enumerate(indices, 1):
        src_file = catalog.get(idx)
        if not src_file:
            print(f"[{i}/{len(indices)}] page {idx}: not in catalog, skip", flush=True)
            continue
        img = orig_dir / src_file
        t0 = time.time()
        try:
            resp = post_page(args.base_url, img, args.book_id, args.title, idx,
                             args.config, args.force, args.timeout)
        except Exception as e:  # noqa: BLE001
            print(f"[{i}/{len(indices)}] page {idx:03d} FAILED after {time.time()-t0:.1f}s: {e}", flush=True)
            records.append({"index": idx, "file": src_file, "ok": False, "error": str(e)})
            continue
        page_id = resp["page_id"]
        out_png = out_dir / f"page-{idx:03d}.png"
        try:
            nbytes = fetch_image(args.base_url, page_id, out_png, timeout=args.timeout)
        except Exception as e:  # noqa: BLE001
            nbytes = 0
            print(f"    image fetch failed: {e}", flush=True)
        if args.save_orig:
            fetch_image(args.base_url, page_id, out_dir / f"page-{idx:03d}.orig.jpg", orig=True)

        blocks = resp.get("blocks", [])
        rec = {
            "index": idx,
            "file": src_file,
            "ok": True,
            "page_id": page_id,
            "cached": resp.get("cached"),
            "context_used": resp.get("context_used"),
            "elapsed_ms": resp.get("elapsed_ms"),
            "wall_s": round(time.time() - t0, 1),
            "out_png": str(out_png),
            "out_bytes": nbytes,
            "blocks": blocks,
        }
        records.append(rec)

        print(f"[{i}/{len(indices)}] page {idx:03d} page_id={page_id} cached={rec['cached']} "
              f"ctx={rec['context_used']} elapsed={rec['elapsed_ms']/1000:.1f}s blocks={len(blocks)} "
              f"wall={rec['wall_s']}s", flush=True)
        for b in blocks:
            print(f"      {b.get('src','')!r}  =>  {b.get('dst','')!r}", flush=True)

    (out_dir / "results.json").write_text(
        json.dumps({"book_id": args.book_id, "title": args.title, "config": args.config,
                    "pages": records, "total_wall_s": round(time.time() - t_all, 1)},
                   ensure_ascii=False, indent=2), encoding="utf-8")

    ok = [r for r in records if r.get("ok")]
    done = [r for r in ok if not r.get("cached")]
    print(f"\n=== done: {len(ok)}/{len(records)} pages, {len(done)} through pipeline, "
          f"wall {time.time()-t_all:.1f}s, results -> {out_dir / 'results.json'}", flush=True)
    if done:
        avg = sum(r["elapsed_ms"] for r in done) / len(done) / 1000
        print(f"=== avg pipeline latency: {avg:.1f}s/page", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
