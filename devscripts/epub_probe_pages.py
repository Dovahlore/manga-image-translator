"""Cheap probe: which EPUB pages actually contain text regions?

Runs the engine pipeline with translator=none (no LLM cost) and reports
HTTP 200 (text found) vs 500 (no text regions -> engine crash bug).

Usage: python devscripts/epub_probe_pages.py 5,15,25,40
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import requests

ENGINE = "http://127.0.0.1:8010/translate/with-form/json"
CFG = json.dumps({"translator": {"translator": "none"}, "render": {"renderer": "none"}})


def main() -> int:
    pages = [int(x) for x in sys.argv[1].split(",") if x.strip()]
    orig = Path("epub_test/orig")
    catalog = {p["index"]: p["file"] for p in
               json.loads((orig / "pages.json").read_text(encoding="utf-8"))["pages"]}
    out = []
    for idx in pages:
        f = orig / catalog[idx]
        t0 = time.time()
        try:
            r = requests.post(ENGINE, files={"image": (f.name, f.read_bytes(), "image/jpeg")},
                              data={"config": CFG}, timeout=300)
            n = len(r.json().get("translations", [])) if r.status_code == 200 else 0
            status = "TEXT" if r.status_code == 200 else "none"
        except Exception as e:  # noqa: BLE001
            status, n = f"err:{type(e).__name__}", 0
        dt = time.time() - t0
        print(f"page {idx:03d}  {status:5s} regions={n:3d}  {dt:5.1f}s", flush=True)
        out.append({"index": idx, "status": status, "regions": n, "sec": round(dt, 1)})
    Path("epub_test/probe_results.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
