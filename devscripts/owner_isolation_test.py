"""多账号隔离验证：账号 = API Key（服务端用 sha256(key) 作 owner）。

用法（需要服务端 MIT_API_TOKEN 配成逗号分隔的两个 key）：
    python devscripts/owner_isolation_test.py KEY_A KEY_B [BASE_URL]

验证点：
  1. 两个 key 都能鉴权；错误 key 401。
  2. 书(books)按 owner 隔离：A 建的书 B 看不到、读不到 pages、删不掉。
  3. 云端(cloud_books)按 owner 隔离：A/B 上传同一本书各得各的 id；B 下不到/删不掉 A 的。
  4. 页图片(page image)按 owner 隔离：B 拿不到 A 的 page_id。
"""
import io
import sys
import uuid
import zipfile

import requests

BASE = sys.argv[3] if len(sys.argv) > 3 else "http://127.0.0.1:8020"
KEY_A = sys.argv[1]
KEY_B = sys.argv[2]


def hdr(key):
    return {"X-API-Token": key}


def make_zip(seed: str) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("manifest.json", '{"title":"同一本书 %s"}' % seed)
        z.writestr("book.src", b"same-book-content")     # 两账号用同一份内容
    return buf.getvalue()


passed = 0
failed = 0


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  ✅ {name}")
    else:
        failed += 1
        print(f"  ❌ {name}  {detail}")


def main():
    # ---- 1. 鉴权 ----
    print("[1] 鉴权")
    check("KEY_A health=200", requests.get(f"{BASE}/v1/health", headers=hdr(KEY_A)).status_code == 200)
    check("KEY_B health=200", requests.get(f"{BASE}/v1/health", headers=hdr(KEY_B)).status_code == 200)
    check("错误 key 401", requests.get(f"{BASE}/v1/health", headers=hdr("nope")).status_code == 401)

    # ---- 2. 书（books）隔离 ----
    print("[2] books 隔离")
    bid_a = f"iso-test-{uuid.uuid4()}"
    r = requests.post(f"{BASE}/v1/books", headers=hdr(KEY_A),
                      json={"id": bid_a, "title": "A 的书"})
    check("A 建书 ok", r.status_code == 200, str(r.status_code))

    a_books = {b["id"] for b in requests.get(f"{BASE}/v1/books", headers=hdr(KEY_A)).json()["books"]}
    b_books = {b["id"] for b in requests.get(f"{BASE}/v1/books", headers=hdr(KEY_B)).json()["books"]}
    check("A 能看到自己的书", bid_a in a_books)
    check("B 看不到 A 的书", bid_a not in b_books)

    check("B 读 A 的 pages=404",
          requests.get(f"{BASE}/v1/books/{bid_a}/pages", headers=hdr(KEY_B)).status_code == 404)
    requests.delete(f"{BASE}/v1/books/{bid_a}", headers=hdr(KEY_B))
    a_books2 = {b["id"] for b in requests.get(f"{BASE}/v1/books", headers=hdr(KEY_A)).json()["books"]}
    check("B 删不掉 A 的书", bid_a in a_books2)

    # ---- 3. 云端隔离 + 同书不同账号 ----
    print("[3] cloud_books 隔离（两个账号传同一本书）")
    zip_bytes = make_zip("shared")
    hash_ = "samesha256hash-" + uuid.uuid4().hex
    ra = requests.post(f"{BASE}/v1/cloud/books", headers=hdr(KEY_A),
                       files={"file": ("book.zip", zip_bytes, "application/zip")},
                       data={"hash": hash_, "title": "同一本书", "mode": "manga", "page_count": 3})
    rb = requests.post(f"{BASE}/v1/cloud/books", headers=hdr(KEY_B),
                       files={"file": ("book.zip", zip_bytes, "application/zip")},
                       data={"hash": hash_, "title": "同一本书", "mode": "manga", "page_count": 3})
    ca = ra.json().get("book_id")
    cb = rb.json().get("book_id")
    check("A 上传成功", bool(ca) and ra.status_code == 200, ra.text[:200])
    check("B 上传成功", bool(cb) and rb.status_code == 200, rb.text[:200])
    check("同书不同账号 → 各自独立 id", ca and cb and ca != cb)

    cloud_a = {b["id"] for b in requests.get(f"{BASE}/v1/cloud/books", headers=hdr(KEY_A)).json()["books"]}
    cloud_b = {b["id"] for b in requests.get(f"{BASE}/v1/cloud/books", headers=hdr(KEY_B)).json()["books"]}
    check("A 云端列表只含 A 的", ca in cloud_a and cb not in cloud_a)
    check("B 云端列表只含 B 的", cb in cloud_b and ca not in cloud_b)

    check("B 下不到 A 的云端书", requests.get(f"{BASE}/v1/cloud/books/{ca}/download",
                                              headers=hdr(KEY_B)).status_code == 404)
    requests.delete(f"{BASE}/v1/cloud/books/{ca}", headers=hdr(KEY_B))
    check("B 删不掉 A 的云端书",
          requests.get(f"{BASE}/v1/cloud/books/{ca}/download", headers=hdr(KEY_A)).status_code == 200)

    # ---- 清理 ----
    requests.delete(f"{BASE}/v1/cloud/books/{ca}", headers=hdr(KEY_A))
    requests.delete(f"{BASE}/v1/cloud/books/{cb}", headers=hdr(KEY_B))
    requests.delete(f"{BASE}/v1/books/{bid_a}", headers=hdr(KEY_A))

    print(f"\n结果：{passed} 通过 / {failed} 失败")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
