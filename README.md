# Manga Image Translator · 漫画翻译

> 端到端的漫画翻译：**Android 漫画阅读器 App** + GPU 翻译引擎 + FastAPI 后端 + 内网穿透。
> 本仓库 Fork 自 [zyddnys/manga-image-translator](https://github.com/zyddnys/manga-image-translator)，在保留其翻译引擎的基础上，新增了手机端 App 与后端服务层。

[![release](https://img.shields.io/github/v/release/Dovahlore/manga-image-translator?label=release)](https://github.com/Dovahlore/manga-image-translator/releases)
[![license](https://img.shields.io/github/license/Dovahlore/manga-image-translator)](LICENSE)

## 简介

自动检测漫画/图片中的文字，识别、翻译后重新排版回图。原项目（zyddnys/manga-image-translator）已提供强大的引擎；本 Fork 把它包装成**手机上能直接用的阅读器**：

- 📱 **Android App**（Kotlin/Compose）：导入漫画文件，一键翻译，边看边译
- 🔌 **app_api**（FastAPI）：书页管理、结果缓存、全书翻译
- 🌐 **内网穿透**（frp）：把本机服务暴露到自己的服务器/域名

## 功能

- 导入 **EPUB / MOBI / AZW**
- 单页翻译、自动预翻、**全书翻译**（后台运行 + 断点续传）
- 阅读器手势：双指捏合、双击放大、单指拖动 1:1 跟手、边缘滑翻页
- 单击切换译文/原图；**UI 自动隐藏**（点空白区域唤出）
- 阅读进度记忆、继续阅读、跳页滑块
- 书库收藏夹、搜索、深浅色主题（跟随系统）
- 译文图 **WebP 无损压缩**（省流量、不降质）

## 截图

| 书库 | 阅读器 |
|------|--------|
| ![](docs/screenshots/library.svg) | ![](docs/screenshots/reader.svg) |

## 架构

```
Android App
   │  HTTP（局域网 http://<本机IP>:8020，或走 frp 线上）
   ▼
app_api (FastAPI) ──▶ engine（GPU 翻译）
   │                     │
   ├── MySQL（书页/任务） └── 模型（约 4.8GB）
   └── Redis（结果缓存）
```

## 快速开始

### 1. 起后端（引擎 + 接口）

```bash
cp app.env.example app.env        # 改成自己的账号密码 / API token
docker compose --env-file app.env -f docker-compose.full.yml up -d
```

首次会拉取约 4.8GB 模型，需要 N 卡 GPU。

### 2. 装 App

- 直接下载 [Release APK](https://github.com/Dovahlore/manga-image-translator/releases) 安装；
- 或自行打包：`cd android && ./gradlew assembleRelease`（需 `android/app/keystore.properties`）。
- 打开 App → 设置 → 服务器地址填 `http://<本机IP>:8020`，API Key 填 `app.env` 里的 `MIT_API_TOKEN`。

### 3. （可选）内网穿透到自己的服务器

见 [docker/tunnel/README.md](docker/tunnel/README.md)：本机 `frpc` → 你的 VPS `frps` → 你的域名。

```bash
docker compose --env-file app.env \
  -f docker-compose.full.yml -f docker-compose.tunnel.yml up -d
```

## 目录

```
android/                    Android App（Kotlin/Compose）
app_api/                    FastAPI 后端
server/                     引擎 HTTP 服务（继承自上游）
docker-compose.full.yml     引擎 + app-api + MySQL + Redis
docker-compose.tunnel.yml   frp 内网穿透（可选）
docker/tunnel/              穿透配置与文档
```

## Fork 来源

基于 [zyddnys/manga-image-translator](https://github.com/zyddnys/manga-image-translator)（GPL-3.0）。翻译引擎、检测 / OCR / 抹字 / 嵌字模型均来自上游；本 Fork 新增 Android App 与后端服务层。
