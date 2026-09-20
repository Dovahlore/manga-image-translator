# 线上模式（内网穿透到自己的 VPS / 域名）

把跑在这台电脑上的 `mit-app-api`（局域网 8020）穿透到你**自己的服务器**，用**你自己的域名**访问。

只加**一个轻量容器** `frpc`（frp 客户端，约 20MB）。本地 `frpc` 主动连到你 VPS 上的 `frps`，把 `app-api:8000` 透传到 VPS，域名（VPS 上的 nginx）再指向它。

**两种模式并存**，App「设置 → 服务器地址」填哪个用哪个：

| 模式 | 地址 | 适用 |
|------|------|------|
| 局域网 | `http://<本机IP>:8020` | 手机和电脑同一 WiFi |
| 线上 | `https://mit.你的域名.com` | 手机在外面（4G/5G/别的网） |

## 拓扑

```
App（手机）
   │  HTTPS（你的域名）
   ▼
VPS: nginx → 127.0.0.1:8020（frps 暴露的端口）
                ▲
                │  frpc 主动外连（一条 TCP 长连接，无需公网 IP/端口映射）
                │
本机: frpc → app-api:8000（docker 网络）
```

## 一、VPS 侧（你自己的服务器，只配一次）

1. 装 frps：下载与本地同版本的 [frp](https://github.com/fatedier/frp/releases)，或 Docker：
   ```bash
   docker run -d --name frps --restart=unless-stopped \
     -v /path/frps.toml:/etc/frp/frps.toml:ro \
     -p 7000:7000 -p 8020:8020 \
     snowdreamtech/frps
   ```
2. 把本目录的 `frps.toml.example` 复制成 `frps.toml`，改 `auth.token`，放到 VPS。
3. 放行防火墙：`7000`（frp 控制）和 `8020`（数据）TCP 入站。

## 二、域名 + HTTPS（VPS 侧）

1. 把 `mit.你的域名.com` 的 A 记录解析到 VPS 公网 IP。
2. 用 certbot 签证书：`certbot certonly --nginx -d mit.你的域名.com`。
3. 把本目录的 `nginx-vps.conf` 复制到 VPS nginx 的 `conf.d/`，改域名后 `nginx -s reload`。

## 三、本机侧（这台 GPU 电脑）

1. 编辑 [frpc.toml](frpc.toml)：
   - `serverAddr` → 你 VPS 的公网 IP 或域名
   - `auth.token` → 和 VPS frps 一致
2. 启动（主栈 + 隧道一起）：
   ```powershell
   docker compose --env-file app.env `
     -f docker-compose.full.yml `
     -f docker-compose.tunnel.yml up -d
   ```
3. 把 `https://mit.你的域名.com` 填进 App「设置 → 服务器地址」。

## 四、安全（公网必做）

`app.env` 里设强随机 `MIT_API_TOKEN`，并在 App「设置 → API Key」填**同一个**值，否则别人拿到公网地址也能调你的接口。

```powershell
[guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N")
```

## 文件清单

| 文件 | 放哪 | 作用 |
|------|------|------|
| `frpc.toml` | 本机（本目录） | frpc 客户端配置（挂进容器） |
| `frps.toml.example` | VPS | frps 服务端配置示例 |
| `nginx-vps.conf` | VPS nginx | 域名 → frps 端口 的 HTTPS 反代 |
| `../docker-compose.tunnel.yml` | 本机项目根 | 只加 frpc 一个容器 |
