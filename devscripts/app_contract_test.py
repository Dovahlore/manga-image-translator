"""App 契约冒烟测试：模拟 Android App 对 app_api 的完整调用序列。

App 实际做的步骤（对应 ReaderViewModel / TranslationApi）：
  1. GET  /v1/health                     （设置页"测试连接"，带 X-API-Token）
  2. POST /v1/pages/translate            （multipart image + book_id + page_index + async_mode=true）
  3. GET  /v1/jobs/{job_id}              轮询 queued/running -> done + page_id
  4. GET  /v1/pages/{page_id}/image      下载译文图 → 本地缓存
  5. DELETE /v1/books/{book_id}          删书（App 长按删书）

用法: python devscripts/app_contract_test.py [--base http://127.0.0.1:8020] [--token ...]
"""
from __future__ import annotations

import argparse
import hashlib
import io
import sys
import time

import requests
from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

DEFAULT_TOKEN = "aFm7-O4B5VD457MKVDNpDK9QFDkjIHbd"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8020")
    ap.add_argument("--token", default=DEFAULT_TOKEN)
    ap.add_argument("--image", default="epub_test/orig/page-030.jpg")
    a = ap.parse_args()
    base = a.base
    H = {"X-API-Token": a.token}
    ok = True

    def check(name, cond, detail):
        nonlocal ok
        print(f"[{'PASS' if cond else 'FAIL'}] {name}  {detail}", flush=True)
        ok = ok and cond

    # 1) 健康（带 token）
    r = requests.get(f"{base}/v1/health", headers=H, timeout=20)
    check("1 健康(带token)", r.status_code == 200, f"HTTP {r.status_code}")

    # 鉴权反证：无 token 应 401
    r = requests.get(f"{base}/v1/health", timeout=20)
    check("1b 鉴权(无token→401)", r.status_code == 401, f"HTTP {r.status_code}")

    # 2) 翻译（async）
    with open(a.image, "rb") as fh:
        raw = fh.read()
    book_id = f"appcontract-{int(time.time())}"
    t0 = time.time()
    r = requests.post(f"{base}/v1/pages/translate",
                      files={"image": (a.image.split("\\")[-1], raw, "image/jpeg")},
                      data={"book_id": book_id, "page_index": "1", "async_mode": "true"},
                      headers=H, timeout=900)
    j = r.json() if r.text else {}
    check("2 翻译(async)", r.status_code == 200 and j.get("job_id"),
          f"HTTP {r.status_code} job_id={j.get('job_id')} status={j.get('status')}")

    # 3) 轮询 job
    job_id = j.get("job_id")
    st = {}
    for _ in range(40):
        st = requests.get(f"{base}/v1/jobs/{job_id}", headers=H, timeout=30).json()
        if st.get("status") in ("done", "failed"):
            break
        time.sleep(2)
    check("3 轮询job→done", st.get("status") == "done" and st.get("page_id"),
          f"status={st.get('status')} page_id={st.get('page_id')} ({time.time()-t0:.1f}s)")

    # 4) 下载译文图
    pid = st.get("page_id")
    r = requests.get(f"{base}/v1/pages/{pid}/image", headers=H, timeout=120)
    sz = Image.open(io.BytesIO(r.content)).size if r.status_code == 200 else None
    check("4 下载译文图", r.status_code == 200 and sz == Image.open(a.image).size,
          f"HTTP {r.status_code} 图 {sz} 原图 {Image.open(a.image).size}")

    # 5) 删书（端到端）
    r = requests.delete(f"{base}/v1/books/{book_id}", headers=H, timeout=30)
    r2 = requests.get(f"{base}/v1/books/{book_id}/pages", headers=H, timeout=30)
    pages_left = len(r2.json().get("pages", [])) if r2.status_code == 200 else -1
    check("5 删书端到端", r.status_code == 200 and pages_left == 0,
          f"DELETE HTTP {r.status_code} 删后 pages={pages_left}")

    print(f"\n==== {'全部通过' if ok else '有失败'} ====")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
