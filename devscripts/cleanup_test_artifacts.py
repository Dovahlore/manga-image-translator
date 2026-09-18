"""Clean up the artifacts produced by this session's API tests.

* removes the test book buckets under D:\\mit-data\\books (CB* / apitest-* / fixcheck / probe-book / test-book)
* removes any 1x1 placeholder PNG results left in the result cache (plus their .json)
* removes the matching rows from MySQL (page_blocks / jobs / pages / page_context / books / cache_index)

Keeps: the real book bucket `yani-neko-vol01` and every non-placeholder cache entry.

Usage: python devscripts/cleanup_test_artifacts.py [--apply]
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

from PIL import Image

DATA = Path(r"D:\mit-data")
BOOK_PREFIXES = ("CB1-", "CB2-", "CB3-", "CB4-", "CB5-", "PB1-", "apitest-")
BOOK_EXACT = {"fixcheck", "probe-book", "test-book"}
DB_USER, DB_PASS, DB_NAME, DB_CONTAINER = "mit", "Alexmercer2000@", "mit", "mit-app-db"


def sql(statement: str) -> None:
    subprocess.run(["docker", "exec", DB_CONTAINER, "mysql", f"-u{DB_USER}", f"-p{DB_PASS}", DB_NAME,
                    "-e", statement], check=False,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    a = ap.parse_args()

    books = [p for p in (DATA / "books").iterdir()
             if p.is_dir() and (p.name.startswith(BOOK_PREFIXES) or p.name in BOOK_EXACT)]
    placeholders = []
    for png in (DATA / "cache" / "result").glob("*.png"):
        try:
            if Image.open(png).size[0] < 8:
                placeholders.append(png)
        except Exception:  # noqa: BLE001
            placeholders.append(png)

    print(f"测试书桶 {len(books)} 个: {[p.name for p in books]}")
    print(f"占位符缓存条目 {len(placeholders)} 个: {[p.name[:24] for p in placeholders]}")

    if not a.apply:
        print("\n（dry-run，加 --apply 才真正删除）")
        return 0

    for p in books:
        for f in sorted(p.rglob("*"), reverse=True):
            f.unlink() if f.is_file() else f.rmdir()
        p.rmdir()
    for png in placeholders:
        png.unlink(missing_ok=True)
        png.with_suffix(".json").unlink(missing_ok=True)

    where = " OR ".join([f"book_id LIKE '{p}%'" for p in BOOK_PREFIXES]
                        + [f"book_id = '{b}'" for b in BOOK_EXACT])
    idwhere = " OR ".join([f"id LIKE '{p}%'" for p in BOOK_PREFIXES]
                          + [f"id = '{b}'" for b in BOOK_EXACT])
    sql(f"DELETE pb FROM page_blocks pb JOIN pages p ON pb.page_id = p.id WHERE {where.replace('book_id', 'p.book_id')};")
    sql(f"DELETE FROM jobs WHERE page_id IN (SELECT id FROM pages WHERE {where});")
    sql(f"DELETE FROM pages WHERE {where};")
    sql(f"DELETE FROM page_context WHERE {where};")
    sql(f"DELETE FROM books WHERE {idwhere};")
    for png in placeholders:
        key = png.stem
        sql(f"DELETE FROM cache_index WHERE cache_key = '{key}';")

    print("\n已清理。剩余书桶:", [p.name for p in (DATA / "books").iterdir() if p.is_dir()])
    left = [p.name for p in (DATA / "cache" / "result").glob("*.png")]
    print(f"剩余缓存条目: {len(left)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
