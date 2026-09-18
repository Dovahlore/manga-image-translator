"""Extract EPUB page images in true reading (spine) order.

Usage:
    python devscripts/epub_extract.py <book.epub> <out_dir> [--limit N]

Writes <out_dir>/page-001.jpg ... and <out_dir>/pages.json describing
index -> {epub_href, file, bytes}.
"""
from __future__ import annotations

import argparse
import json
import os
import posixpath
import re
import xml.etree.ElementTree as ET
import zipfile

IMG_EXT = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def spine_documents(zf: zipfile.ZipFile) -> tuple[str, list[str]]:
    opf_name = next(n for n in zf.namelist() if n.lower().endswith(".opf"))
    root = ET.fromstring(zf.read(opf_name))
    base = posixpath.dirname(opf_name)

    id_to_href: dict[str, str] = {}
    for el in root.iter():
        if _local(el.tag) == "item" and el.get("id") and el.get("href"):
            id_to_href[el.get("id", "")] = el.get("href", "")

    docs: list[str] = []
    for el in root.iter():
        if _local(el.tag) == "itemref":
            href = id_to_href.get(el.get("idref", ""))
            if href:
                docs.append(posixpath.normpath(posixpath.join(base, href)) if base else href)
    return opf_name, docs


def doc_images(zf: zipfile.ZipFile, doc: str) -> list[str]:
    """Return image hrefs referenced by an XHTML doc, in document order."""
    try:
        raw = zf.read(doc).decode("utf-8", "replace")
    except KeyError:
        return []
    base = posixpath.dirname(doc)
    found: list[str] = []
    for m in re.finditer(r"""(?:src|href)\s*=\s*["']([^"']+)["']""", raw):
        href = m.group(1).strip()
        if posixpath.splitext(href)[1].lower() in IMG_EXT:
            found.append(posixpath.normpath(posixpath.join(base, href)) if base else href)
    # fall back to <image xlink:href> in case of odd quoting
    for m in re.finditer(r"""<image[^>]*?xlink:href\s*=\s*["']([^"']+)["']""", raw):
        href = m.group(1).strip()
        p = posixpath.normpath(posixpath.join(base, href)) if base else href
        if p not in found:
            found.append(p)
    return found


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("epub")
    ap.add_argument("out_dir")
    ap.add_argument("--limit", type=int, default=0, help="only first N pages")
    args = ap.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    with zipfile.ZipFile(args.epub) as zf:
        opf, docs = spine_documents(zf)
        records: list[dict] = []
        seen_hashes: set[str] = set()
        for doc in docs:
            for href in doc_images(zf, doc):
                if href in seen_hashes:
                    continue
                seen_hashes.add(href)
                try:
                    data = zf.read(href)
                except KeyError:
                    continue
                ext = posixpath.splitext(href)[1].lower() or ".jpg"
                idx = len(records) + 1
                name = f"page-{idx:03d}{ext}"
                with open(os.path.join(args.out_dir, name), "wb") as fh:
                    fh.write(data)
                records.append({"index": idx, "epub_href": href, "file": name, "bytes": len(data)})
                if args.limit and len(records) >= args.limit:
                    break
            if args.limit and len(records) >= args.limit:
                break

    with open(os.path.join(args.out_dir, "pages.json"), "w", encoding="utf-8") as fh:
        json.dump({"epub": os.path.abspath(args.epub), "opf": opf, "pages": records}, fh, ensure_ascii=False, indent=2)

    print(f"extracted {len(records)} pages -> {args.out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
