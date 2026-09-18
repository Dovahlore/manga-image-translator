"""极简 MySQL 连接池 + 常用读写。

所有函数是同步的；异步端点里请用 starlette.concurrency.run_in_threadpool 调用，
避免阻塞事件循环。
"""

import queue
import threading
import time
from pathlib import Path

import pymysql
from pymysql.cursors import DictCursor

from . import settings as S

_pool: "queue.LifoQueue" = queue.LifoQueue()
_MAX_POOL = 8
_create_lock = threading.Lock()


def _new_conn():
    return pymysql.connect(
        host=S.DB_HOST, port=S.DB_PORT, user=S.DB_USER, password=S.DB_PASSWORD,
        database=S.DB_NAME, charset="utf8mb4", autocommit=True,
        cursorclass=DictCursor, connect_timeout=10, read_timeout=60, write_timeout=60,
    )


def _acquire():
    try:
        conn = _pool.get_nowait()
        conn.ping(reconnect=True)
        return conn
    except queue.Empty:
        return _new_conn()
    except Exception:
        return _new_conn()


def _release(conn):
    try:
        if _pool.qsize() < _MAX_POOL:
            _pool.put(conn)
        else:
            conn.close()
    except Exception:
        try:
            conn.close()
        except Exception:
            pass


def query(sql, args=None):
    conn = _acquire()
    try:
        with conn.cursor() as cur:
            cur.execute(sql, args or ())
            return cur.fetchall()
    finally:
        _release(conn)


def query_one(sql, args=None):
    rows = query(sql, args)
    return rows[0] if rows else None


def execute(sql, args=None):
    conn = _acquire()
    try:
        with conn.cursor() as cur:
            cur.execute(sql, args or ())
            return cur.lastrowid, cur.rowcount
    finally:
        _release(conn)


def _split_statements(text: str):
    out = []
    for raw in text.split(";"):
        lines = [ln for ln in raw.splitlines() if not ln.strip().startswith("--")]
        stmt = "\n".join(lines).strip()
        if stmt:
            out.append(stmt)
    return out


def init_schema(retries: int = 30, delay: float = 2.0):
    """建表 + 迁移（幂等）。容器编排时 MySQL 可能还没就绪，这里做重试。"""
    stmts = _split_statements(Path(__file__).with_name("schema.sql").read_text(encoding="utf-8"))
    last_err = None
    for attempt in range(1, retries + 1):
        try:
            conn = _new_conn()
            with conn.cursor() as cur:
                # ---- 迁移老库（幂等）----
                # 1) cache_index 只写不读 → 直接删
                cur.execute("DROP TABLE IF EXISTS cache_index")
                # 2) page_blocks 老结构是自增 id 主键 → 改成 (page_id, idx) 复合主键
                cur.execute(
                    "SELECT COUNT(*) AS c FROM information_schema.COLUMNS "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='page_blocks' AND COLUMN_NAME='id'"
                )
                if cur.fetchone()["c"] > 0:
                    cur.execute("DROP TABLE IF EXISTS page_blocks")
                # 3) jobs 老结构没 page_id 外键 → 重建
                cur.execute(
                    "SELECT COUNT(*) AS c FROM information_schema.KEY_COLUMN_USAGE "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='jobs' AND CONSTRAINT_NAME='fk_jobs_page'"
                )
                if cur.fetchone()["c"] == 0:
                    cur.execute("DROP TABLE IF EXISTS jobs")
                # 4) 老库的 pages 没有外键 → 整体重建 4 张表（升级只在第一次发生）
                cur.execute(
                    "SELECT COUNT(*) AS c FROM information_schema.KEY_COLUMN_USAGE "
                    "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pages' AND CONSTRAINT_NAME='fk_pages_book'"
                )
                if cur.fetchone()["c"] == 0:
                    cur.execute("SET FOREIGN_KEY_CHECKS=0")
                    for t in ("page_blocks", "page_context", "pages", "books"):
                        cur.execute(f"DROP TABLE IF EXISTS {t}")
                    cur.execute("SET FOREIGN_KEY_CHECKS=1")
                # ---- 建表（IF NOT EXISTS）----
                for s in stmts:
                    cur.execute(s)
            conn.close()
            return len(stmts)
        except Exception as e:      # noqa: BLE001
            last_err = e
            print(f"[db] 等待 MySQL 就绪… ({attempt}/{retries}) {type(e).__name__}: {e}", flush=True)
            time.sleep(delay)
    raise RuntimeError(f"MySQL 连接/建表失败: {last_err}")


def ping() -> bool:
    try:
        query_one("SELECT 1 AS ok")
        return True
    except Exception:
        return False
