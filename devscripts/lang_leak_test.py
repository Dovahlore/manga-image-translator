"""Does the engine honour target_lang, or does it leak the previous language?

Sequence on a freshly restarted engine:
  1. ENG   (process history empty)
  2. CHS
  3. ENG   (process history now holds Chinese translations)

If step 1 is English and step 3 is Chinese, the in-process cross-page context
leaks the previous language into the requested target language.
"""
from __future__ import annotations

import json
import re
import sys

import requests

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

ENGINE = "http://127.0.0.1:8010/translate/with-form/json"
IMG = open(r"epub_test/orig/page-025.jpg", "rb").read()

CJK = re.compile(r"[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uff66-\uff9f]")
LAT = re.compile(r"[A-Za-z]")


def script_of(s: str) -> str:
    c, l = len(CJK.findall(s)), len(LAT.findall(s))
    if c and c >= l:
        return f"CJK(cjk={c},lat={l})"
    if l:
        return f"Latin(cjk={c},lat={l})"
    return "?"


def probe(tag: str, target: str) -> None:
    r = requests.post(ENGINE, files={"image": ("p.jpg", IMG, "image/jpeg")},
                      data={"config": json.dumps({"translator": {"translator": "deepseek",
                                                                 "target_lang": target}})},
                      timeout=600)
    j = r.json()
    print(f"\n[{tag}] target_lang={target}  HTTP {r.status_code}")
    for t in j.get("translations", [])[:3]:
        for k, v in t["text"].items():
            if k.upper() == target.upper():
                print(f"    目标键 {k}: {v!r}  -> {script_of(v)}")
    keys = [list(t["text"].keys()) for t in j.get("translations", [])[:1]]
    print(f"    keys={keys}")


probe("1 干净进程", "ENG")
probe("2 切成中文", "CHS")
probe("3 再要英文", "ENG")
