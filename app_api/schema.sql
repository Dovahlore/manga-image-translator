-- ============================================================
--  mit-app-api 数据库结构（MySQL 8+ / InnoDB / utf8mb4）
--
--  原则：DB 只存"长期要查的索引 + 结构化结果"；图片/缓存走磁盘文件（L1）。
--  4 张表，外键 + 级联删除：
--    books(id) ← pages.book_id          ON DELETE CASCADE
--    books(id) ← page_context.book_id   ON DELETE CASCADE
--    pages(id) ← page_blocks.page_id    ON DELETE CASCADE
--
--  已砍掉（冗余 / 只写不读）：
--    jobs         —— 与 pages.status/attempts/error 完全重复，且响应里不暴露 job_id，客户端查不到
--    cache_index  —— 只 INSERT/UPDATE 从不 SELECT，纯摆设（真正的缓存就是 cache/result/*.png 文件）
--
--  首次启动由 app_api.main → db.init_schema 执行；
--  老库升级由 db.init_schema 里的迁移步骤处理（幂等）。
-- ============================================================

CREATE TABLE IF NOT EXISTS books (
  id          VARCHAR(191) PRIMARY KEY,
  title       VARCHAR(512) NULL,
  format      VARCHAR(32)  NULL,          -- epub / mobi / cbz / folder / unknown
  page_count  INT          NULL,
  order_dir   VARCHAR(8)   NULL,          -- ltr / rtl（日漫右开本）
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pages (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  book_id     VARCHAR(191) NOT NULL,
  page_index  INT          NOT NULL,
  order_dir   VARCHAR(8)   NULL,
  orig_sha1   CHAR(40)     NOT NULL,
  config_hash CHAR(40)     NOT NULL,      -- 只含影响管线的配置，不含上下文
  orig_path   VARCHAR(512) NULL,
  out_path    VARCHAR(512) NULL,
  json_path   VARCHAR(512) NULL,          -- 完整结果文件（含每块 text/background），给校对/下载用
  status      VARCHAR(16)  NOT NULL DEFAULT 'pending', -- pending/running/done/failed
  attempts    INT          NOT NULL DEFAULT 0,
  error       TEXT         NULL,
  elapsed_ms  INT          NULL,
  tokens      INT          NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uniq_book_page (book_id, page_index),
  KEY idx_sha1 (orig_sha1),
  CONSTRAINT fk_pages_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每页的块级结构化结果（src/dst/bbox/颜色/角度）——长期留存：
--   * /v1/pages/{id}/json 直接从这里读，不碰磁盘文件
--   * 未来"换翻译器重译"可复用这些框/原文，省掉重跑检测+OCR
-- (page_id, idx) 天然唯一，直接做复合主键（省掉自增 id 和二级索引）
CREATE TABLE IF NOT EXISTS page_blocks (
  page_id  BIGINT NOT NULL,
  idx      INT    NOT NULL,
  minx     INT NULL, miny INT NULL, maxx INT NULL, maxy INT NULL,
  angle    DOUBLE NULL,
  prob     DOUBLE NULL,
  fg       VARCHAR(32) NULL, bg VARCHAR(32) NULL,
  src_text TEXT NULL,
  dst_text TEXT NULL,
  PRIMARY KEY (page_id, idx),
  CONSTRAINT fk_pageblocks_page FOREIGN KEY (page_id) REFERENCES pages (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 跨页上下文：按 book_id 分桶，杜绝"两本书串页"；书删了跟着删
CREATE TABLE IF NOT EXISTS page_context (
  book_id    VARCHAR(191) NOT NULL,
  page_index INT          NOT NULL,
  src_text   TEXT NULL,
  dst_text   TEXT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (book_id, page_index),
  CONSTRAINT fk_pagecontext_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 任务表：给 App 看进度（POST /v1/pages/translate?async=1 → 轮询 /v1/jobs/{id}）
CREATE TABLE IF NOT EXISTS jobs (
  id          VARCHAR(36) PRIMARY KEY,
  page_id     BIGINT      NULL,           -- 完成后回填；页删了跟着删
  action      VARCHAR(32) NOT NULL,       -- translate / retranslate
  status      VARCHAR(16) NOT NULL,       -- queued / running / done / failed
  attempts    INT NOT NULL DEFAULT 0,
  config_json JSON NULL,
  error       TEXT NULL,
  queued_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  started_at  DATETIME NULL,
  finished_at DATETIME NULL,
  KEY idx_status (status),
  CONSTRAINT fk_jobs_page FOREIGN KEY (page_id) REFERENCES pages (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
