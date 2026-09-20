"""mit-app-api —— 给上层 App 用的漫画翻译服务。

职责边界：
  * 重活（检测 / OCR / 翻译 / 抹字 / 嵌字）交给 mit-engine（GPU 常驻）
  * 本服务负责：数据库记录、结果缓存、书页管理、上下文组装、重试与重译

对外核心就一个动作：**给我一页图，还我一页译文图 + 结构化结果**。
"""

import asyncio
import base64
import copy
import hashlib
import io
import json
import shutil
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Dict, List, Optional

import httpx
import redis.asyncio as aioredis
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from starlette.concurrency import run_in_threadpool

from . import db
from . import settings as S

# ---------------------------------------------------------------- 基础设施

_redis: Optional[aioredis.Redis] = None
_engine_sem = asyncio.Semaphore(1)          # 引擎是单 worker（GPU 独占），这里排队
_book_locks: Dict[str, asyncio.Lock] = {}   # 同一本书的页串行，保证上下文顺序


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _redis
    S.CACHE_DIR.mkdir(parents=True, exist_ok=True)
    S.BOOKS_DIR.mkdir(parents=True, exist_ok=True)
    n = await run_in_threadpool(db.init_schema)
    print(f"[app-api] 数据库就绪，执行了 {n} 条 DDL", flush=True)
    try:
        _redis = aioredis.from_url(S.REDIS_URL, password=S.REDIS_PASSWORD or None,
                                   decode_responses=True)
        await _redis.ping()
        print("[app-api] Redis 连接成功", flush=True)
    except Exception as e:      # noqa: BLE001
        print(f"[app-api] Redis 不可用（缓存锁降级为进程内）: {e}", flush=True)
        _redis = None
    cleanup_task = asyncio.create_task(_retention_loop())
    yield
    cleanup_task.cancel()
    if _redis:
        await _redis.aclose()


# ---------------------------------------------------------------- 保留期清理
# 翻译结果只留 N 天（App 本地有缓存，服务端 14 天后自动删，省空间也省成本）

async def _cleanup_cache_files(days: int) -> int:
    if not S.CACHE_DIR.exists():
        return 0
    cutoff = time.time() - days * 86400
    n = 0
    for p in S.CACHE_DIR.glob("*.png"):
        try:
            if p.stat().st_mtime < cutoff:
                p.unlink(missing_ok=True)
                n += 1
        except Exception:      # noqa: BLE001
            pass
    return n


async def _cleanup_expired_once() -> None:
    days = S.RETENTION_DAYS
    if days <= 0:
        return
    rows = await run_in_threadpool(
        db.query,
        "SELECT id, orig_path, out_path, json_path FROM pages "
        "WHERE updated_at < NOW() - INTERVAL %s DAY", (days,))
    for r in rows:
        for col in ("orig_path", "out_path", "json_path"):
            p = r.get(col)
            if p:
                try:
                    Path(p).unlink(missing_ok=True)
                except Exception:      # noqa: BLE001
                    pass
    if rows:
        # 删 pages 会级联删 page_blocks 与 jobs
        await run_in_threadpool(db.execute,
                                "DELETE FROM pages WHERE updated_at < NOW() - INTERVAL %s DAY", (days,))
    await run_in_threadpool(db.execute,
                            "DELETE FROM page_context WHERE created_at < NOW() - INTERVAL %s DAY", (days,))
    n_files = await _cleanup_cache_files(days)
    if rows or n_files:
        print(f"[app-api] 保留期清理：删 {len(rows)} 页结果、{n_files} 个缓存图片（>{days} 天）", flush=True)


async def _retention_loop() -> None:
    while True:
        try:
            await _cleanup_expired_once()
        except Exception as e:      # noqa: BLE001
            print(f"[app-api] 保留期清理出错: {e}", flush=True)
        await asyncio.sleep(6 * 3600)   # 每 6 小时扫一次


app = FastAPI(title="mit-app-api", version="0.1.0", lifespan=lifespan)


async def auth(x_api_token: Optional[str] = Header(default=None)):
    if S.API_TOKEN and x_api_token != S.API_TOKEN:
        raise HTTPException(401, detail="invalid X-API-Token")


# ---------------------------------------------------------------- 工具函数

def deep_merge(base: dict, override: dict) -> dict:
    out = copy.deepcopy(base)
    for k, v in (override or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = deep_merge(out[k], v)
        else:
            out[k] = v
    return out


def pipeline_hash(cfg: dict) -> str:
    """缓存键的一部分：只含影响管线结果的配置，不含 context_text（软输入）。"""
    c = {k: v for k, v in cfg.items() if k not in S.CACHE_IGNORED_KEYS}
    return hashlib.sha1(json.dumps(c, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def sha1_bytes(b: bytes) -> str:
    return hashlib.sha1(b).hexdigest()


def build_context_text(items: List[Dict[str, str]]) -> str:
    """把若干页的译文拼成引擎认识的上下文格式（与 _build_prev_context 一致）。

    items 按时间从早到晚；正式格式：
        Here are the previous translation results for reference:
        <|1|>句子
        <|2|>句子
    """
    lines: List[str] = []
    for it in items or []:
        text = (it.get("dst") or it.get("src") or "").strip()
        for ln in text.splitlines():
            ln = ln.strip()
            if ln:
                lines.append(ln)
    if not lines:
        return ""
    numbered = [f"<|{i + 1}|>{s}" for i, s in enumerate(lines)]
    return "Here are the previous translation results for reference:\n" + "\n".join(numbered)


def book_context_from_db(book_id: str, page_index: int, pages: int) -> str:
    if pages <= 0:
        return ""
    rows = db.query(
        "SELECT dst_text FROM page_context WHERE book_id=%s AND page_index<%s "
        "ORDER BY page_index DESC LIMIT %s",
        (book_id, page_index, pages),
    )
    items = [{"dst": r["dst_text"]} for r in reversed(rows)]
    return build_context_text(items)


def get_book_lock(book_id: str) -> asyncio.Lock:
    lock = _book_locks.get(book_id)
    if lock is None:
        lock = asyncio.Lock()
        _book_locks[book_id] = lock
    return lock


async def call_engine(image_bytes: bytes, cfg: dict) -> dict:
    """调用引擎的一条龙接口（一次管线运行同时拿译文图 + 结构化结果）。"""
    files = {"image": ("page.png", image_bytes, "image/png")}
    data = {"config": json.dumps(cfg, ensure_ascii=False)}
    timeout = httpx.Timeout(S.ENGINE_TIMEOUT, connect=20.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        r = await client.post(f"{S.ENGINE_URL}/translate/with-form/page", files=files, data=data)
        if r.status_code != 200:
            raise HTTPException(502, detail=f"engine {r.status_code}: {r.text[:500]}")
        return r.json()


# ---------------------------------------------------------------- 引擎结果校验
# 不引入 Pillow：直接读 PNG/JPEG 头拿宽高（app-api 镜像刻意保持精简）

def _png_size(data: bytes) -> Optional[tuple[int, int]]:
    if len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR":
        return (int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big"))
    return None


def _jpeg_size(data: bytes) -> Optional[tuple[int, int]]:
    i, n = 2, len(data)
    while i + 9 < n:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7 or marker == 0xFF:
            i += 2
            continue
        seg_len = int.from_bytes(data[i + 2:i + 4], "big")
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            return (int.from_bytes(data[i + 7:i + 9], "big"),
                    int.from_bytes(data[i + 5:i + 7], "big"))
        i += 2 + seg_len
    return None


def image_size(data: bytes) -> Optional[tuple[int, int]]:
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return _png_size(data)
    if data[:2] == b"\xff\xd8":
        return _jpeg_size(data)
    return None


def engine_image_ok(engine_img: bytes, source: bytes) -> tuple[bool, str]:
    """引擎返回的图是否可信：不是 1x1 占位符，且尺寸与原图一致。

    引擎的 `_is_streaming_mode` 是挂在常驻实例上的开关，网页端流式翻译一次就会把它
    置 True，之后所有非流式调用只拿到 1x1 白图（真图已存进引擎 result 目录）。
    以前这里不校验，于是白图被当成正常结果写进 L1 缓存和 pages/out.png 永久留毒。
    """
    size = image_size(engine_img)
    if size is None:
        return False, "engine 返回的图无法解析（既不是 PNG 也不是 JPEG）"
    w, h = size
    if w < 8 or h < 8:
        return False, (f"engine 返回占位符白图 {w}x{h}：mit-engine 的流式占位符开关卡住了，"
                       f"重启引擎（.\\mit-docker.ps1 restart）或打 share.py 的补丁")
    src = image_size(source)
    if src is not None and (abs(w - src[0]) > 1 or abs(h - src[1]) > 1):
        return False, f"engine 返回图尺寸 {w}x{h} 与原图 {src[0]}x{src[1]} 不一致"
    return True, ""


# ---------------------------------------------------------------- L1 结果缓存（Redis）
# 分层：图片按内容 hash 落本地磁盘（cache/result/*.png，长期复用），
#       结构化结果 JSON 放 Redis（带 TTL，命中即省掉整条管线）。
# Redis 挂了就当"没缓存"直接跑管线，不影响正确性。

def _cache_key(img_sha: str, cfg_hash: str) -> str:
    return f"mit:result:{img_sha}:{cfg_hash}"


async def cache_get(key: str) -> str | None:
    if not _redis:
        return None
    try:
        return await _redis.get(key)
    except Exception:      # noqa: BLE001
        return None


async def cache_set(key: str, value: str) -> None:
    if not _redis:
        return
    try:
        await _redis.set(key, value, ex=S.CACHE_TTL or None)
    except Exception:      # noqa: BLE001
        pass


async def cache_delete(key: str) -> None:
    if not _redis:
        return
    try:
        await _redis.delete(key)
    except Exception:      # noqa: BLE001
        pass


def normalize_blocks(result: dict, target_lang: str = "CHS", include_background: bool = False) -> List[dict]:
    """把引擎返回的每个文本块整理成 App 友好的结构。

    引擎返回的 text 是 {语言代码: 文本}，比如：
      {'ja': 'これは漫画の', 'CHS': '这是漫画的'}          （目标 CHS）
      {'ENG': 'This is a manga.', 'ja': 'これは漫画の'}   （目标 ENG）
    所以不能"取第一个非 CHS 的键当原文"——目标语言一变就会标反。
    这里以配置里的 target_lang 为准定位译文，其余键里挑一个当原文。
    """
    target_lang = (target_lang or "CHS").upper()
    blocks = []
    for b in (result or {}).get("translations", []) or []:
        text = b.get("text") or {}
        dst = text.get(target_lang)
        if dst is None:
            for alt in ("CHS", "CHT", "ENG", "JPN", "KOR"):
                if alt in text:
                    dst = text[alt]
                    break
        dst = dst or ""
        src = next((v for k, v in text.items() if k.upper() != target_lang and v != dst), "")
        item = {
            "bbox": [b.get("minX"), b.get("minY"), b.get("maxX"), b.get("maxY")],
            "angle": b.get("angle"),
            "prob": b.get("prob"),
            "fg": (b.get("text_color") or {}).get("fg"),
            "bg": (b.get("text_color") or {}).get("bg"),
            "src": src,
            "dst": dst,
            "text": text,
        }
        if include_background:
            item["background"] = b.get("background")
        blocks.append(item)
    return blocks


def _to_webp_lossless(data: bytes) -> bytes:
    """PNG/JPEG → WebP 无损（省流量且不降质）。转失败或体积更大就原样返回。

    放在「保存译文图时」调用：翻译本身要几十秒，这里多花一两百毫秒完全无感，
    下载路径只是直接发预生成好的 .webp，零额外延迟。
    """
    if not S.IMAGE_COMPRESS:
        return data
    try:
        from PIL import Image
        img = Image.open(io.BytesIO(data))
        has_alpha = img.mode in ("RGBA", "LA", "PA") or (
            img.mode == "P" and "transparency" in img.info)
        img = img.convert("RGBA" if has_alpha else "RGB")
        buf = io.BytesIO()
        # lossless=True 保证像素级无损；quality/method 只是压缩用力程度（越大越慢越小）
        img.save(buf, format="WEBP", lossless=True, quality=90, method=6)
        out = buf.getvalue()
        return out if len(out) < len(data) else data   # 万一更大就保留原 PNG
    except Exception:      # noqa: BLE001
        return data


def page_paths(book_id: str, page_index: int) -> tuple[Path, Path, Path]:
    d = S.BOOKS_DIR / _safe(book_id) / f"{page_index:06d}"
    return d / "orig.png", d / "out.webp", d / "result.json"


def _safe(name: str) -> str:
    return "".join(c if c.isalnum() or c in "-_.:" else "_" for c in name)[:120] or "unknown"


# ---------------------------------------------------------------- 接口

@app.get("/v1/health", dependencies=[Depends(auth)])
async def health():
    engine_ok, engine_info = False, None
    try:
        async with httpx.AsyncClient(timeout=10) as c:
            r = await c.post(f"{S.ENGINE_URL}/queue-size")
            engine_ok = r.status_code == 200
            engine_info = {"queue_size": r.json()}
    except Exception as e:      # noqa: BLE001
        engine_info = {"error": str(e)}
    db_ok = await run_in_threadpool(db.ping)
    redis_ok = False
    try:
        redis_ok = bool(_redis and await _redis.ping())
    except Exception:
        redis_ok = False
    return {
        "status": "ok" if (engine_ok and db_ok) else "degraded",
        "engine": {"url": S.ENGINE_URL, "ok": engine_ok, **(engine_info or {})},
        "db": {"ok": db_ok},
        "redis": {"ok": redis_ok},
        "data_dir": str(S.DATA_DIR),
    }


@app.get("/v1/capabilities", dependencies=[Depends(auth)])
async def capabilities():
    return {
        "default_config": S.DEFAULT_CONFIG,
        "context_pages_default": S.CONTEXT_PAGES,
        "online_translators": ["deepseek", "gemini", "gemini_2stage", "chatgpt",
                               "chatgpt_2stage", "groq", "custom_openai"],
        "offline_translators": ["sugoi", "jparacrawl", "jparacrawl_big", "m2m100",
                                "m2m100_big", "m2m100_hf", "nllb", "nllb_big",
                                "mbart50", "qwen2", "qwen2_big", "sakura"],
        "target_langs_hint": ["CHS", "CHT", "ENG", "JPN", "KOR"],
        "endpoints": {
            "translate": "POST /v1/pages/translate (multipart)",
            "image": "GET /v1/pages/{page_id}/image?orig=1",
            "json": "GET /v1/pages/{page_id}/json",
            "retranslate": "POST /v1/pages/{page_id}/retranslate",
        },
    }


@app.post("/v1/pages/translate", dependencies=[Depends(auth)])
async def translate_page(
    image: UploadFile = File(..., description="整页图片"),
    book_id: Optional[str] = Form(None),
    title: Optional[str] = Form(None),
    page_index: Optional[int] = Form(None),
    order_dir: Optional[str] = Form(None, description="ltr / rtl"),
    config: Optional[str] = Form(None, description="JSON，覆盖默认管线配置"),
    context: Optional[str] = Form(None, description="JSON 数组 [{src,dst}]，App 侧维护的最近若干页"),
    force: bool = Form(False, description="true=忽略缓存强制重翻"),
    include_background: bool = Form(False, description="是否返回每块的抹字底图(base64，较大)"),
    async_mode: bool = Form(False, description="true=立即返回 job_id，App 轮询 /v1/jobs/{id} 看进度"),
):
    """**核心接口**：一页图进 → 译文图 + 结构化结果出（带缓存/上下文/重试）。

    async_mode=true 时变为"发任务 + 轮询"：立刻返回 {job_id, status, job_url}，
    后台跑管线，App 用 GET /v1/jobs/{job_id} 看进度（done 后带 page_id）。
    """
    t0 = time.time()
    raw = await image.read()
    if not raw:
        raise HTTPException(400, detail="empty image")

    overrides: Dict[str, Any] = {}
    if config:
        try:
            overrides = json.loads(config)
        except json.JSONDecodeError as e:
            raise HTTPException(400, detail=f"config 不是合法 JSON: {e}")
    cfg = deep_merge(S.DEFAULT_CONFIG, overrides)

    # ---- 上下文：App 传的优先；否则用数据库里同一本书的历史 ----
    ctx_items = None
    if context:
        try:
            ctx_items = json.loads(context)
        except json.JSONDecodeError as e:
            raise HTTPException(400, detail=f"context 不是合法 JSON: {e}")
    real_book_id = book_id or f"_adhoc_{sha1_bytes(raw)[:12]}"
    real_page_index = page_index if page_index is not None else 0

    if ctx_items:
        cfg["context_text"] = build_context_text(ctx_items)
        ctx_source = "client"
    elif book_id and S.CONTEXT_PAGES > 0:
        cfg["context_text"] = await run_in_threadpool(
            book_context_from_db, book_id, real_page_index, S.CONTEXT_PAGES)
        ctx_source = "db" if cfg["context_text"] else "none"
    else:
        ctx_source = "none"

    img_sha = sha1_bytes(raw)
    cfg_hash = pipeline_hash(cfg)
    cache_key = f"{img_sha}:{cfg_hash}"
    png_path = S.CACHE_DIR / f"{cache_key}.png"   # 图片缓存：本地磁盘
    redis_key = _cache_key(img_sha, cfg_hash)     # 结构化结果缓存：Redis

    # ---- 任务登记（sync / async 共用，App 都能拿 job_id 看状态）----
    job_id = str(uuid.uuid4())
    await run_in_threadpool(
        db.execute,
        "INSERT INTO jobs (id, action, status, config_json) VALUES (%s,'translate','queued',%s)",
        (job_id, json.dumps({k: v for k, v in cfg.items() if k != "context_text"}, ensure_ascii=False)))

    if async_mode:
        asyncio.create_task(_run_translate_job(
            job_id, raw, cfg, real_book_id, real_page_index, order_dir, title,
            img_sha, cfg_hash, cache_key, png_path, redis_key, ctx_source, include_background, force))
        return {"job_id": job_id, "status": "queued", "job_url": f"/v1/jobs/{job_id}"}

    await run_in_threadpool(db.execute, "UPDATE jobs SET status='running', started_at=NOW() WHERE id=%s", (job_id,))
    try:
        result = await _execute_translate(
            raw, cfg, real_book_id, real_page_index, order_dir, title,
            img_sha, cfg_hash, cache_key, png_path, redis_key, ctx_source, include_background, force)
    except Exception as e:      # noqa: BLE001
        await run_in_threadpool(db.execute,
                                "UPDATE jobs SET status='failed', finished_at=NOW(), error=%s WHERE id=%s",
                                (str(e)[:2000], job_id))
        raise
    await run_in_threadpool(db.execute,
                            "UPDATE jobs SET status='done', finished_at=NOW(), attempts=1, page_id=%s WHERE id=%s",
                            (result["page_id"], job_id))
    result["job_id"] = job_id
    return JSONResponse(result)


async def _run_translate_job(job_id, raw, cfg, real_book_id, real_page_index, order_dir, title,
                             img_sha, cfg_hash, cache_key, png_path, redis_key,
                             ctx_source, include_background, force) -> None:
    """后台执行翻译任务，更新 jobs 状态供 App 轮询。"""
    await run_in_threadpool(db.execute, "UPDATE jobs SET status='running', started_at=NOW() WHERE id=%s", (job_id,))
    try:
        result = await _execute_translate(
            raw, cfg, real_book_id, real_page_index, order_dir, title,
            img_sha, cfg_hash, cache_key, png_path, redis_key, ctx_source, include_background, force)
        await run_in_threadpool(db.execute,
                                "UPDATE jobs SET status='done', finished_at=NOW(), attempts=1, page_id=%s WHERE id=%s",
                                (result["page_id"], job_id))
    except Exception as e:      # noqa: BLE001
        await run_in_threadpool(db.execute,
                                "UPDATE jobs SET status='failed', finished_at=NOW(), error=%s WHERE id=%s",
                                (str(e)[:2000], job_id))


async def _execute_translate(raw, cfg, real_book_id, real_page_index, order_dir, title,
                             img_sha, cfg_hash, cache_key, png_path, redis_key,
                             ctx_source, include_background, force) -> dict:
    """跑缓存检查 → 命中直接回，未命中跑整条管线并落库。返回 _page_response 的 dict。"""
    t0 = time.time()
    # 同一本书串行：既保证上下文顺序，也避免"同页并发落库"竞态
    # （否则两个请求同时 DELETE+INSERT page_blocks，(page_id,idx) 复合主键会撞 Duplicate entry）
    lock = get_book_lock(real_book_id)
    async with lock:
        # ---- L1 结果缓存（Redis 存 JSON，图片落磁盘）----
        if not force:
            cached_payload_json = await cache_get(redis_key)
            if cached_payload_json and png_path.exists():
                cached_png = png_path.read_bytes()
                ok_cached, why = engine_image_ok(cached_png, raw)
                if not ok_cached:
                    # 自愈：清掉被污染（例如 1x1 白图）的条目，落到下面重跑管线
                    print(f"[app-api] 丢弃被污染的缓存条目 {cache_key}: {why}", flush=True)
                    png_path.unlink(missing_ok=True)
                    await cache_delete(redis_key)
                    cached_payload_json = None
            if cached_payload_json:
                payload = json.loads(cached_payload_json)
                img_b64 = base64.b64encode(png_path.read_bytes()).decode()
                page_id = await run_in_threadpool(
                    _persist_page, real_book_id, real_page_index, order_dir, title, img_sha, cfg_hash,
                    raw, png_path.read_bytes(), payload, "done", 0, None, int((time.time() - t0) * 1000),
                    payload.get("tokens"))
                # 缓存命中的页也要登记上下文，否则同书下一页的 ctx 会静默退化成 none
                dst_all = "\n".join(b["dst"] for b in payload.get("blocks", []) if b.get("dst"))
                src_all = "\n".join(b["src"] for b in payload.get("blocks", []) if b.get("src"))
                if dst_all or src_all:
                    await run_in_threadpool(
                        db.execute,
                        "REPLACE INTO page_context (book_id, page_index, src_text, dst_text) "
                        "VALUES (%s,%s,%s,%s)",
                        (real_book_id, real_page_index, src_all, dst_all))
                return _page_response(page_id, cache_key, True, payload, img_b64, include_background,
                                      int((time.time() - t0) * 1000), ctx_source,
                                      real_book_id, real_page_index, img_sha)

        # ---- 未命中：真正跑一次管线（引擎单 worker，信号量串行）----
        async with _engine_sem:
            try:
                data = await call_engine(raw, cfg)
                img_bytes = base64.b64decode(data["image_b64"])
                # 白图/尺寸不符一律当故障，绝不让它进缓存和 pages/out.png
                ok_img, why = engine_image_ok(img_bytes, raw)
                if not ok_img:
                    raise HTTPException(502, detail=why)
                result = data.get("result") or {}
                blocks = normalize_blocks(result, cfg["translator"]["target_lang"], include_background=True)
                payload = {
                    "blocks": blocks,
                    "raw_result": result,
                    "config": {k: v for k, v in cfg.items() if k != "context_text"},
                    "context_used": ctx_source,
                    "created_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                }
                png_path.write_bytes(img_bytes)                                     # 图片 → 磁盘
                await cache_set(redis_key, json.dumps(payload, ensure_ascii=False))  # 结果 → Redis
                page_id = await run_in_threadpool(
                    _persist_page, real_book_id, real_page_index, order_dir, title, img_sha, cfg_hash,
                    raw, img_bytes, payload, "done", 1, None, int((time.time() - t0) * 1000), None)
                # 写入上下文表（供同书后续页使用）
                dst_all = "\n".join(b["dst"] for b in blocks if b.get("dst"))
                src_all = "\n".join(b["src"] for b in blocks if b.get("src"))
                await run_in_threadpool(
                    db.execute,
                    "REPLACE INTO page_context (book_id, page_index, src_text, dst_text) VALUES (%s,%s,%s,%s)",
                    (real_book_id, real_page_index, src_all, dst_all))
                if _redis:
                    try:
                        await _redis.incr("mit:stats:pages_translated")
                    except Exception:
                        pass
            except Exception as e:      # noqa: BLE001
                await run_in_threadpool(
                    _persist_page, real_book_id, real_page_index, order_dir, title, img_sha, cfg_hash,
                    raw, None, None, "failed", 1, str(e)[:2000], int((time.time() - t0) * 1000), None)
                raise

    img_b64 = base64.b64encode(img_bytes).decode()
    return _page_response(page_id, cache_key, False, payload, img_b64, include_background,
                          int((time.time() - t0) * 1000), ctx_source,
                          real_book_id, real_page_index, img_sha)


def _persist_page(book_id, page_index, order_dir, title, img_sha, cfg_hash, raw,
                  out_bytes, payload, status, attempts, error, elapsed_ms, tokens) -> int:
    db.execute(
        "INSERT INTO books (id, title, order_dir) VALUES (%s,%s,%s) "
        "ON DUPLICATE KEY UPDATE title=COALESCE(VALUES(title), title), "
        "order_dir=COALESCE(VALUES(order_dir), order_dir)",
        (book_id, title, order_dir))
    orig_path, out_path, json_path = page_paths(book_id, page_index)
    orig_path.parent.mkdir(parents=True, exist_ok=True)
    if raw:
        orig_path.write_bytes(raw)
    db.execute(
        """INSERT INTO pages (book_id, page_index, order_dir, orig_sha1, config_hash,
                              orig_path, out_path, json_path, status, attempts, error, elapsed_ms, tokens)
           VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
           ON DUPLICATE KEY UPDATE
             order_dir=COALESCE(VALUES(order_dir), order_dir),
             orig_sha1=VALUES(orig_sha1), config_hash=VALUES(config_hash),
             orig_path=VALUES(orig_path), out_path=VALUES(out_path), json_path=VALUES(json_path),
             status=VALUES(status), attempts=attempts+VALUES(attempts), error=VALUES(error),
             elapsed_ms=VALUES(elapsed_ms), tokens=VALUES(tokens)""",
        (book_id, page_index, order_dir, img_sha, cfg_hash,
         str(orig_path), str(out_path) if out_bytes else None,
         str(json_path) if payload else None, status, attempts, error, elapsed_ms, tokens))
    row = db.query_one("SELECT id FROM pages WHERE book_id=%s AND page_index=%s", (book_id, page_index))
    page_id = row["id"]
    if out_bytes:
        out_path.write_bytes(_to_webp_lossless(out_bytes))
    if payload:
        json_path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        db.execute("DELETE FROM page_blocks WHERE page_id=%s", (page_id,))
        for i, b in enumerate(payload.get("blocks", [])):
            x1, y1, x2, y2 = (b.get("bbox") or [None, None, None, None])
            db.execute(
                """INSERT INTO page_blocks (page_id, idx, minx, miny, maxx, maxy, angle, prob,
                                            fg, bg, src_text, dst_text)
                   VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                (page_id, i, x1, y1, x2, y2, b.get("angle"), b.get("prob"),
                 json.dumps(b.get("fg")), json.dumps(b.get("bg")), b.get("src"), b.get("dst")))
    return page_id


def _page_response(page_id, cache_key, cached, payload, img_b64, include_background, elapsed_ms, ctx_source,
                   book_id=None, page_index=None, orig_sha1=None) -> dict:
    blocks = payload.get("blocks", [])
    if not include_background:
        blocks = [{k: v for k, v in b.items() if k != "background"} for b in blocks]
    return {
        "page_id": page_id,
        "cache_key": cache_key,
        "cached": cached,
        "status": "done",
        "elapsed_ms": elapsed_ms,
        "context_used": ctx_source,
        # ---- 页身份回显：用来一眼确认"服务器收到的到底是哪一页" ----
        # orig_sha1 = 本次请求图片字节的 sha1（和 ?orig=1 取回的图对得上）
        # page_index 是回显（没传就是 None/0），book_id 为 None 时说明落进了 _adhoc_<sha1> 桶
        "orig_sha1": orig_sha1,
        "book_id": book_id,
        "page_index": page_index,
        "blocks": blocks,
        "image_url": f"/v1/pages/{page_id}/image",
        "image_b64": img_b64 if include_background else None,
    }


@app.get("/v1/pages/{page_id}/image", dependencies=[Depends(auth)])
async def page_image(page_id: int, orig: int = Query(0, description="1=原图")):
    row = await run_in_threadpool(db.query_one, "SELECT * FROM pages WHERE id=%s", (page_id,))
    if not row:
        raise HTTPException(404, detail="page not found")
    path = row["orig_path"] if orig else (row["out_path"] or row["orig_path"])
    if not path or not Path(path).exists():
        raise HTTPException(404, detail="image not available")
    media = "image/webp" if str(path).lower().endswith(".webp") else "image/png"
    return FileResponse(path, media_type=media,
                        headers={"Cache-Control": "public, max-age=31536000, immutable"})


@app.get("/v1/pages/{page_id}/json", dependencies=[Depends(auth)])
async def page_json(page_id: int):
    row = await run_in_threadpool(db.query_one, "SELECT * FROM pages WHERE id=%s", (page_id,))
    if not row:
        raise HTTPException(404, detail="page not found")
    blocks = await run_in_threadpool(
        db.query, "SELECT idx,minx,miny,maxx,maxy,angle,prob,src_text,dst_text FROM page_blocks "
                  "WHERE page_id=%s ORDER BY idx", (page_id,))
    return {
        "page": {k: row[k] for k in ("id", "book_id", "page_index", "status", "attempts",
                                     "error", "elapsed_ms", "orig_sha1")},
        "blocks": blocks,
    }


@app.post("/v1/pages/{page_id}/retranslate", dependencies=[Depends(auth)])
async def retranslate(page_id: int, body: Optional[dict] = None):
    """效果不好时重译：可换翻译器/目标语言/术语表，或补上下文。默认 force=true。"""
    body = body or {}
    row = await run_in_threadpool(db.query_one, "SELECT * FROM pages WHERE id=%s", (page_id,))
    if not row:
        raise HTTPException(404, detail="page not found")
    orig = Path(row["orig_path"] or "")
    if not orig.exists():
        raise HTTPException(410, detail="原图已不在本地，无法重译")
    raw = orig.read_bytes()

    overrides = body.get("config") or {}
    cfg = deep_merge(S.DEFAULT_CONFIG, overrides)
    ctx_text = ""
    if body.get("context"):
        ctx_text = build_context_text(body["context"])
    elif row["book_id"] and S.CONTEXT_PAGES > 0:
        ctx_text = await run_in_threadpool(
            book_context_from_db, row["book_id"], row["page_index"], S.CONTEXT_PAGES)
    if ctx_text:
        cfg["context_text"] = ctx_text

    async with get_book_lock(row["book_id"]), _engine_sem:
        data = await call_engine(raw, cfg)
        img_bytes = base64.b64decode(data["image_b64"])
        blocks = normalize_blocks(data.get("result") or {}, cfg["translator"]["target_lang"],
                                  include_background=True)
        payload = {"blocks": blocks, "raw_result": data.get("result") or {},
                   "config": {k: v for k, v in cfg.items() if k != "context_text"},
                   "context_used": "retranslate", "created_at": time.strftime("%Y-%m-%d %H:%M:%S")}
        out_path = Path(row["out_path"] or page_paths(row["book_id"], row["page_index"])[1])
        json_path = Path(row["json_path"] or page_paths(row["book_id"], row["page_index"])[2])
        await run_in_threadpool(_persist_page, row["book_id"], row["page_index"], row["order_dir"],
                                None, row["orig_sha1"], pipeline_hash(cfg), None, img_bytes,
                                payload, "done", 1, None, None, None)
    return {"page_id": page_id, "status": "done", "attempts": row["attempts"] + 1,
            "orig_sha1": row["orig_sha1"], "book_id": row["book_id"], "page_index": row["page_index"],
            "blocks": [{k: v for k, v in b.items() if k != "background"} for b in blocks],
            "out_path": str(out_path)}


@app.post("/v1/books", dependencies=[Depends(auth)])
async def upsert_book(body: dict):
    bid = body.get("id") or body.get("book_id")
    if not bid:
        raise HTTPException(400, detail="缺少 id")
    await run_in_threadpool(
        db.execute,
        """INSERT INTO books (id, title, format, page_count, order_dir) VALUES (%s,%s,%s,%s,%s)
           ON DUPLICATE KEY UPDATE title=COALESCE(VALUES(title),title),
             format=COALESCE(VALUES(format),format),
             page_count=COALESCE(VALUES(page_count),page_count),
             order_dir=COALESCE(VALUES(order_dir),order_dir)""",
        (bid, body.get("title"), body.get("format"), body.get("page_count"), body.get("order_dir")))
    return {"ok": True, "book_id": bid}


@app.get("/v1/books", dependencies=[Depends(auth)])
async def list_books():
    return {"books": await run_in_threadpool(
        db.query, "SELECT b.*, "
                  "(SELECT COUNT(*) FROM pages p WHERE p.book_id=b.id) AS translated_pages, "
                  "(SELECT COUNT(*) FROM pages p WHERE p.book_id=b.id AND p.status='done') AS done_pages, "
                  "(SELECT COUNT(*) FROM pages p WHERE p.book_id=b.id AND p.status='failed') AS failed_pages "
                  "FROM books b ORDER BY b.updated_at DESC LIMIT 200")}


@app.get("/v1/books/{book_id}/pages", dependencies=[Depends(auth)])
async def book_pages(book_id: str):
    return {"book_id": book_id, "pages": await run_in_threadpool(
        db.query, "SELECT id, page_index, status, attempts, error, elapsed_ms, tokens, "
                  "config_hash, updated_at FROM pages WHERE book_id=%s ORDER BY page_index",
        (book_id,))}


@app.post("/v1/books/translate-all", dependencies=[Depends(auth)])
async def translate_all(
    book_id: str = Form(...),
    title: Optional[str] = Form(None),
    order_dir: Optional[str] = Form(None),
    page_count: Optional[int] = Form(None),
    config: Optional[str] = Form(None),
    force: bool = Form(False),
    page_indices: Optional[str] = Form(None, description="JSON 数组，如 [0,2,5]；缺省按上传顺序 0..N-1"),
    images: List[UploadFile] = File(...),
):
    """**全书翻译**：一次上传整本书的页图（可只传未翻过的页），服务端按顺序后台翻译。

    返回 book_job_id 后，App 轮询 GET /v1/books/{book_id}/pages 看每页进度、下载译文图。
    已翻好的页会命中 L1 缓存（不会重跑管线），force=true 才强制重翻。
    """
    if not images:
        raise HTTPException(400, detail="no images")

    raws: List[bytes] = []
    for img in images:
        b = await img.read()
        if not b:
            raise HTTPException(400, detail=f"empty image: {img.filename}")
        raws.append(b)

    if page_indices:
        try:
            indices = [int(i) for i in json.loads(page_indices)]
        except Exception as e:      # noqa: BLE001
            raise HTTPException(400, detail=f"page_indices 不是合法 JSON 数组: {e}")
    else:
        indices = list(range(len(raws)))
    if len(indices) != len(raws):
        raise HTTPException(400, detail="page_indices 与 images 数量不一致")

    overrides: Dict[str, Any] = {}
    if config:
        try:
            overrides = json.loads(config)
        except json.JSONDecodeError as e:
            raise HTTPException(400, detail=f"config 不是合法 JSON: {e}")
    cfg = deep_merge(S.DEFAULT_CONFIG, overrides)

    # 登记书元信息（page_count 供书库展示）
    await run_in_threadpool(
        db.execute,
        "INSERT INTO books (id, title, format, page_count, order_dir) VALUES (%s,%s,%s,%s,%s) "
        "ON DUPLICATE KEY UPDATE title=COALESCE(VALUES(title),title), "
        "format=COALESCE(VALUES(format),format), page_count=COALESCE(VALUES(page_count),page_count), "
        "order_dir=COALESCE(VALUES(order_dir),order_dir)",
        (book_id, title, None, page_count, order_dir))

    job_id = str(uuid.uuid4())
    await run_in_threadpool(
        db.execute,
        "INSERT INTO jobs (id, action, status, config_json) VALUES (%s,'translate_all','queued',%s)",
        (job_id, json.dumps({k: v for k, v in cfg.items() if k != "context_text"}, ensure_ascii=False)))
    asyncio.create_task(_run_translate_all(job_id, book_id, title, order_dir, cfg, indices, raws, force))
    return {"book_job_id": job_id, "status": "queued", "total": len(raws)}


async def _run_translate_all(job_id, book_id, title, order_dir, cfg, indices, raws, force) -> None:
    """后台按顺序翻完整本书：逐页复用 _execute_translate（含 L1 缓存 + 上下文 + 落库）。"""
    await run_in_threadpool(db.execute, "UPDATE jobs SET status='running', started_at=NOW() WHERE id=%s", (job_id,))
    try:
        for idx, raw in zip(indices, raws):
            img_sha = sha1_bytes(raw)
            cfg_hash = pipeline_hash(cfg)
            cache_key = f"{img_sha}:{cfg_hash}"
            png_path = S.CACHE_DIR / f"{cache_key}.png"
            redis_key = _cache_key(img_sha, cfg_hash)

            # 顺序跑，前一页的 page_context 已落库，下一页自动带上跨页上下文
            ctx_source = "none"
            cfg_page = cfg
            if S.CONTEXT_PAGES > 0:
                ctx_text = await run_in_threadpool(book_context_from_db, book_id, idx, S.CONTEXT_PAGES)
                if ctx_text:
                    cfg_page = dict(cfg)
                    cfg_page["context_text"] = ctx_text
                    ctx_source = "db"

            try:
                await _execute_translate(raw, cfg_page, book_id, idx, order_dir, title,
                                         img_sha, cfg_hash, cache_key, png_path, redis_key,
                                         ctx_source, False, force)
            except Exception as e:      # noqa: BLE001
                await run_in_threadpool(
                    _persist_page, book_id, idx, order_dir, title, img_sha, cfg_hash,
                    raw, None, None, "failed", 1, str(e)[:2000], 0, None)
        await run_in_threadpool(db.execute, "UPDATE jobs SET status='done', finished_at=NOW() WHERE id=%s", (job_id,))
    except Exception as e:      # noqa: BLE001
        await run_in_threadpool(db.execute,
                                "UPDATE jobs SET status='failed', finished_at=NOW(), error=%s WHERE id=%s",
                                (str(e)[:2000], job_id))


@app.delete("/v1/books/{book_id}", dependencies=[Depends(auth)])
async def delete_book(book_id: str):
    """删书（端到端）：DB 里的书/页/块/上下文/任务靠外键级联删，磁盘上的页文件一并删。"""
    pages = await run_in_threadpool(
        db.query, "SELECT COUNT(*) AS n FROM pages WHERE book_id=%s", (book_id,))
    await run_in_threadpool(db.execute, "DELETE FROM books WHERE id=%s", (book_id,))
    await run_in_threadpool(_rmtree_safe, S.BOOKS_DIR / _safe(book_id))
    return {"ok": True, "book_id": book_id, "deleted_pages": pages[0]["n"] if pages else 0}


def _rmtree_safe(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path, ignore_errors=True)


@app.get("/v1/jobs/{job_id}", dependencies=[Depends(auth)])
async def job_status(job_id: str):
    """任务进度：POST /v1/pages/translate?async=1 后轮询这个。"""
    row = await run_in_threadpool(db.query_one, "SELECT * FROM jobs WHERE id=%s", (job_id,))
    if not row:
        raise HTTPException(404, detail="job not found")
    return row


@app.get("/", include_in_schema=False)
async def root():
    return {"service": "mit-app-api", "docs": "/docs", "health": "/v1/health"}
