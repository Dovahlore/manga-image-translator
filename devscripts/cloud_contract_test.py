"""云同步后端契约测试：模拟 App 的同步/下载/去重/收藏夹/取消同步 全流程。

用法: python devscripts/cloud_contract_test.py [--base ...] [--token ...] [--user alice]
"""
from __future__ import annotations

import argparse
import hashlib
import io
import sys
import time
import zipfile

import requests
from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

TOKEN = "aFm7-O4B5VD457MKVDNpDK9QFDkjIHbd"


def make_zip(content: bytes) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", '{"title":"测试漫画","mode":"manga","page_count":1}')
        z.writestr("book.epub", content)
    return buf.getvalue()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8020")
    ap.add_argument("--token", default=TOKEN)
    ap.add_argument("--user", default="alice")
    a = ap.parse_args()
    H = {"X-API-Token": a.token, "X-User-Id": a.user}
    ok = True

    def check(name, cond, detail):
        nonlocal ok
        print(f"[{'PASS' if cond else 'FAIL'}] {name}  {detail}", flush=True)
        ok = ok and cond

    epub = b"FAKEEPUB-" + str(time.time()).encode()
    h = hashlib.sha256(epub).hexdigest()
    z = make_zip(epub)

    # 1) 上传（带收藏夹）
    r = requests.post(f"{a.base}/v1/cloud/books", headers=H,
                      files={"file": ("book.zip", z, "application/zip")},
                      data={"title": "测试漫画", "folder": "收藏夹A", "mode": "manga",
                            "hash": h, "fingerprint": "fp1", "page_count": "1"}, timeout=60)
    j = r.json() if r.text else {}
    bid = j.get("book_id")
    check("1 上传", r.status_code == 200 and j.get("existed") is False, f"HTTP {r.status_code} id={bid}")

    # 2) 列表
    r = requests.get(f"{a.base}/v1/cloud/books", headers=H, timeout=30).json()
    check("2 列表", any(b["id"] == bid for b in r["books"]), f"{len(r['books'])} 本, folder={r['books'][0]['folder'] if r['books'] else None}")

    # 3) lookup 去重
    r = requests.get(f"{a.base}/v1/cloud/books/lookup", headers=H, params={"hash": h}, timeout=30).json()
    check("3 lookup", r.get("existed") is True and r["book_id"] == bid, str(r))

    # 4) 重复上传 → 返回已有 id，不重复存
    r = requests.post(f"{a.base}/v1/cloud/books", headers=H,
                      files={"file": ("book.zip", z, "application/zip")},
                      data={"hash": h}, timeout=60).json()
    check("4 去重(同hash)", r.get("existed") is True and r["book_id"] == bid, str(r))

    # 5) 下载 zip → 字节一致
    r = requests.get(f"{a.base}/v1/cloud/books/{bid}/download", headers=H, timeout=60)
    got = r.content
    check("5 下载", r.status_code == 200 and got == z, f"HTTP {r.status_code} size={len(got)}")

    # 6) 收藏夹列表
    r = requests.get(f"{a.base}/v1/cloud/folders", headers=H, timeout=30).json()
    check("6 收藏夹", any(f["name"] == "收藏夹A" and f["book_count"] == 1 for f in r["folders"]), str(r))

    # 7) 账号隔离：另一账号看不到
    H2 = {"X-API-Token": a.token, "X-User-Id": "bob"}
    r = requests.get(f"{a.base}/v1/cloud/books", headers=H2, timeout=30).json()
    check("7 账号隔离", len(r["books"]) == 0, f"bob 看到 {len(r['books'])} 本")

    # 8) 取消同步 → 删 zip + 记录；翻译结果也级联删
    #    先用该 cloud id 翻一页（模拟设备翻译），再删，验证 pages 也没了
    img = open("_runtime/work/input/test_page.png", "rb").read()
    rr = requests.post(f"{a.base}/v1/pages/translate", headers=H,
                       files={"image": ("p.jpg", img, "image/jpeg")},
                       data={"book_id": bid, "page_index": "1"}, timeout=900)
    r = requests.delete(f"{a.base}/v1/cloud/books/{bid}", headers=H, timeout=30)
    r2 = requests.get(f"{a.base}/v1/books/{bid}/pages", headers=H, timeout=30)
    pages_left = len(r2.json().get("pages", [])) if r2.status_code == 200 else -1
    check("8 取消同步(级联删译文)", r.status_code == 200 and pages_left == 0,
          f"DELETE {r.status_code} 删后 pages={pages_left}")

    print(f"\n==== {'全部通过' if ok else '有失败'} ====")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
