# Docker 启动说明（RTX 50 系 / CUDA 12.8 适配版）

针对 **RTX 5070 Laptop（Blackwell, sm_120）** 做的 Docker 方案。上游自带的 `Dockerfile`
基镜像是 `pytorch/pytorch:2.5.1-cuda11.8-cudnn9-runtime`，**CUDA 11.8 不支持 sm_120**，
在 50 系卡上会拿不到 GPU（报 `no kernel image is available` 或直接退回 CPU）。

## 文件说明

| 文件 | 作用 |
|---|---|
| `Dockerfile.cu128` | CUDA 12.8 基镜像（PyTorch 2.8）；不在构建期下载模型，不装 gimp |
| `docker-compose.full.yml` | **全栈编排**：引擎 + MySQL + Redis + App 接口（项目名 `mit`） |
| `docker-compose.app.yml` | 只是 `include: docker-compose.full.yml` 的别名，老命令照样能用 |
| `docker-compose.cu128.yml` | 上游那份的 CUDA 12.8 变体（只用于「只跑引擎」的场景） |
| `mit-app.ps1` | **全栈一键脚本**：up/down/status/build/test/logs/sql/redis |
| `mit-docker.ps1` | 引擎侧脚本：probe/gpu-test/net-test/build/cli/shell/api；up/down 已转发给 compose |
| `mit-smoketest.ps1` | 引擎冒烟测试 |

## 快速开始

```powershell
cd "D:\mayuq\OneDrive - bupt.edu.cn\Projects\manga-image-translator"

# —— 全栈（引擎 + MySQL + Redis + App 接口）——
.\mit-app.ps1 up             # 首次会自动构建镜像
.\mit-app.ps1 status         # 健康检查：引擎/DB/Redis/App 接口
.\mit-app.ps1 test           # 用 _runtime\work\input\test_page.png 跑一遍"一页进→译文图出"
# 浏览器： http://localhost:8010  （引擎网页端）   http://localhost:8020/docs （App 接口 Swagger）

# —— 只操作引擎时 ——
.\mit-docker.ps1 probe       # 看看卡、运行时、端口、能用的基镜像
.\mit-docker.ps1 gpu-test    # 确认容器里能跑 nvidia-smi
.\mit-docker.ps1 net-test    # 确认容器能访问 GitHub / HuggingFace（下载模型要）
.\mit-docker.ps1 build       # 只重建引擎镜像 mit:cu128
.\mit-docker.ps1 up          # 只起 engine 这一个服务
```

> 等价的原生 compose 命令：
> `docker compose --env-file app.env -f docker-compose.full.yml up -d`
> （`ps` / `logs -f engine` / `down` 同理；`down` 不会删 `_runtime` 里的数据）

## 两种运行方式

**A. 常驻服务（推荐，也是你后面做 App 后端要用的形态）**

```powershell
.\mit-docker.ps1 up
```
内部跑的是 `python server/main.py --host 0.0.0.0 --port 8000 --start-instance --use-gpu`：
FastAPI 网关 + 一个自动拉起的常驻 worker（`manga_translator shared`，内部 8001）。
模型只加载一次，之后每次请求都很快。常用端点：

- `GET  /` 网页 UI；`/manual` 手改排版说明
- `POST /translate/with-form/image` 上传图片直接拿译文图
- `POST /translate/with-form/json` **结构化结果**（坐标 / 原文 / 译文 / 颜色 / 抹字底图 base64）← 做 App 校对界面用这个
- `POST /translate/batch/images` 批量，返回 zip
- `POST /translate/with-form/image/stream` 带进度码的流式返回
- `POST /queue-size` 排队长度

**B. 命令行批处理（不常驻，每次重新加载模型）**

```powershell
.\mit-docker.ps1 cli -Input D:\mit-work\input
.\mit-docker.ps1 cli -Input .\_runtime\work\input -ArgLine "--translator sakura --target-lang CHS --ocr mocr --detector ctd"
```

## 目录与端口约定

**引擎和 App 接口现在是同一套 compose**（`docker-compose.full.yml`），数据全部在**项目内**的 `_runtime\` 下：

| 项目内路径 | 容器内 | 说明 |
|---|---|---|
| `_runtime\models` | engine:`/app/models` | 模型 + HF 缓存（约 4.8GB） |
| `_runtime\data\app` | app-api:`/data` | 书页 `books/` 与结果缓存 `cache/` |
| `_runtime\data\mysql` / `redis` | 各自数据目录 | MySQL 数据目录 / Redis AOF |
| `_runtime\work\input` / `output` | engine:`/input`、`/output` | 批处理输入输出、`test_page.png` |
| `secret.env`（项目根） | 注入 engine 环境变量 | `DEEPSEEK_API_KEY` 等，已 gitignore |

| 宿主机端口 | 容器内 | 说明 |
|---|---|---|
| `localhost:8010` | engine `8000` | 引擎 Web/API（避开你已有的 web/nginx/mysql 容器） |
| `localhost:8020` | app-api `8000` | App 接口 |
| `127.0.0.1:33063` / `56379` | MySQL / Redis | 避开已占用的 33062 / 6379 |
| `localhost:5003` | `5003` | 仅 `api` 模式用 |

改路径：`app.env` 里设 `MIT_RUNTIME=D:/mit`（compose 整棵搬走），或给 `mit-docker.ps1` 传 `-ModelsDir/-WorkRoot`。
改端口：`app.env` 里的 `MIT_WEB_PORT` / `MIT_APP_PORT` / `MIT_DB_HOST_PORT` / `MIT_REDIS_HOST_PORT`。

## 代理（关键，模型要从 GitHub / HuggingFace 下）

容器里的 `127.0.0.1` 是容器自己，打不到宿主机的 Clash。方案：

1. 在 Clash 里打开 **「允许局域网连接 / Allow LAN」**；
2. 脚本默认注入 `HTTP_PROXY=HTTPS_PROXY=http://host.docker.internal:7897`；
3. HuggingFace 默认走镜像 `HF_ENDPOINT=https://hf-mirror.com`。

不想要代理（或打不开 LAN）：加 `-NoProxy`，例如 `.\mit-docker.ps1 up -NoProxy`。
`net-test` 会分别告诉你"直连 / 走代理"哪个通。

## 已知坑

- **显存只有 8 GB**：跑得动，但别同时开大分辨率放大（`--upscale-ratio`）和 `inpainter=sd`。
  需要省显存可以给 worker 传 `--use-gpu-limited`（把离线翻译器放 CPU）。
- **docker-compose.yml（上游那份）不是给引擎用的**，它只 build `front`（React 前端）。
- **模型不在 git 里**，也不在镜像里：第一次翻译时下到 `/app/models`，也就是项目内 `_runtime\models`，
  换机器/重建镜像都不用重下（`_runtime/` 已 gitignore）。
- **Docker Desktop 内存上限约 7.8 GB**（当前 WSL 配置），构建时可以适当调大。 
- `rusty-manga-image-translator` 这个轮子托管在 GitHub Pages，
  装不上会导致 `import manga_translator` 直接失败——构建日志里确认那句
  `rusty OK; torch ... cuda ... avail` 有没有打印出来。
