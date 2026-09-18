# ============================================================
#  manga-image-translator Docker 助手（Windows + Docker Desktop + NVIDIA）
#
#  常用：
#    .\mit-docker.ps1 probe      # 体检：显卡直通、运行时、端口占用、候选基镜像
#    .\mit-docker.ps1 gpu-test   # 容器内跑 nvidia-smi，确认 GPU 真能进去
#    .\mit-docker.ps1 net-test   # 容器内测代理/HF/GitHub 通不通（模型要下载）
#    .\mit-docker.ps1 build      # 构建镜像 mit:cu128（CUDA 12.8，支持 RTX 50 系）
#    .\mit-docker.ps1 up         # 起常驻服务：Web UI + API -> http://localhost:8010
#    .\mit-docker.ps1 cli -Input D:\pics\manga    # 命令行批处理
#    .\mit-docker.ps1 down       # 停掉
#
#  路径默认都在项目里：模型 <仓库>\_runtime\models，工作目录 <仓库>\_runtime\work
#  （docker-compose.full.yml 用的是同一处，所以 CLI / 网页端 / App 共享同一份模型）
#  想搬到别处： -ModelsDir D:\models\mit -WorkRoot D:\mit-work，并给 compose 设 MIT_RUNTIME
# ============================================================

param(
    [Parameter(Position = 0)]
    [ValidateSet('probe','gpu-test','net-test','build','up','down','restart','logs','status','shell','cli','api')]
    [string]$Action = 'probe',

    [string]$Input,
    [string]$Output,
    [string]$ArgLine = '',                       # 追加给 manga_translator 的命令行参数
    [string]$BaseImage = '',                     # 留空 = 自动从候选里挑一个可用的
    [string]$RegistryMirror = 'docker.m.daocloud.io',  # 国内拉 Docker Hub 用；留空 = 直连官方源
    [string]$ModelsDir = '',                     # 留空 = <仓库>\_runtime\models
    [string]$WorkRoot  = '',                     # 留空 = <仓库>\_runtime\work
    [int]$WebPort      = 8010,
    [int]$ApiHostPort  = 5003,
    [string]$ProxyUrl  = 'http://host.docker.internal:7897',
    [switch]$NoProxy,
    [string]$HfEndpoint = 'https://hf-mirror.com',
    [switch]$BuildProxy                          # 构建期也走代理（需 Clash 打开“允许局域网连接”）
)

$ErrorActionPreference = 'Stop'
$Image     = 'mit:cu128'
$Container = 'mit-engine'
$Root      = $PSScriptRoot

# 数据/模型默认落在项目内（与 docker-compose.full.yml 一致）
if (-not $PSBoundParameters.ContainsKey('ModelsDir') -or -not $ModelsDir) { $ModelsDir = Join-Path $Root '_runtime\models' }
if (-not $PSBoundParameters.ContainsKey('WorkRoot')  -or -not $WorkRoot)  { $WorkRoot  = Join-Path $Root '_runtime\work' }

# CUDA 12.8+ 才能驱动 Blackwell(sm_120 / RTX 50 系)；最后一个只是兜底（GPU 会不可用）
$Candidates = @(
    'pytorch/pytorch:2.8.0-cuda12.8-cudnn9-runtime',
    'pytorch/pytorch:2.7.1-cuda12.8-cudnn9-runtime',
    'pytorch/pytorch:2.9.0-cuda12.8-cudnn9-runtime',
    'pytorch/pytorch:2.8.0-cuda12.9-cudnn9-runtime'
)

function Say  ($m) { Write-Host "[mit] $m" -ForegroundColor Cyan }
function Warn ($m) { Write-Host "[mit] $m" -ForegroundColor Yellow }
function Die  ($m) { Write-Host "[mit] $m" -ForegroundColor Red; exit 1 }

# PS5.1 会把原生命令的 stderr 当成终止错误；凡是“允许失败”的 docker 调用都包在这里
function Invoke-Quiet ([scriptblock]$sb) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $sb *> $null } finally { $ErrorActionPreference = $prev }
}

# 官方名 -> 镜像站前缀：pytorch/pytorch:x => <mirror>/pytorch/pytorch:x，python:x => <mirror>/library/python:x
function Mirror ([string]$img) {
    if (-not $RegistryMirror) { return $img }
    if ($img -match '^[^/]+\.[^/]+/') { return $img }   # 已带域名前缀，别重复加
    if ($img.Split('/').Count -eq 1) { return "$RegistryMirror/library/$img" }
    return "$RegistryMirror/$img"
}

# PS 5.1 下原生命令的 stderr 在 ErrorActionPreference=Stop 时会变成终止错误，必须临时压住
function Test-Manifest ([string]$img) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & docker manifest inspect $img *> $null
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    return ($code -eq 0)
}

function Resolve-Base ([string]$want) {
    # 显式指定了基镜像 → 直接用，不做联网预检。
    # 理由：BuildKit 自己会从「本地构建缓存」解析这个 tag，registry 连不上也能重建；
    # 之前的预检反而会在断网/镜像站抽风时把构建拦死。
    if ($want) { Say "使用指定基镜像：$want"; return $want }
    foreach ($t in @($Candidates | ForEach-Object { Mirror $_ }) + $Candidates) {
        if (Test-Manifest $t) { return $t }
        Warn "取不到：$t"
    }
    return $null
}

function Ensure-Dirs {
    foreach ($d in @($ModelsDir, (Join-Path $ModelsDir 'hf'),
                     (Join-Path $WorkRoot 'input'), (Join-Path $WorkRoot 'output'))) {
        if (-not (Test-Path $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
    }
}

function Env-Args {
    $a = @('-e', 'HF_HOME=/app/models/hf')
    if ($HfEndpoint) { $a += @('-e', "HF_ENDPOINT=$HfEndpoint") }
    # hf-mirror：把代码里硬编码的 huggingface.co 直链改写到镜像（HF_URL_MIRROR 生效点见 utils/generic.py）
    $a += @('-e', 'HF_URL_MIRROR=https://hf-mirror.com')
    # 跨页上下文：翻译每一页时带上前一页作为参考（0 = 关闭）
    $a += @('-e', "MIT_CONTEXT_SIZE=$(if ($env:MIT_CONTEXT_SIZE) { $env:MIT_CONTEXT_SIZE } else { '1' })")
    # 关掉 HuggingFace 的 xet 桥（cas-bridge.xethub.hf.co）：国内常 SSL EOF 导致权重下不全
    $a += @('-e', 'HF_HUB_DISABLE_XET=1')
    if (-not $NoProxy -and $ProxyUrl) {
        $a += @('-e', "HTTP_PROXY=$ProxyUrl", '-e', "HTTPS_PROXY=$ProxyUrl", '-e', 'NO_PROXY=localhost,127.0.0.1,api.deepseek.com,sakura')
    }
    return $a
}

function Mount-Args {
    Ensure-Dirs
    $a = @('-v', "$(Join-Path $WorkRoot 'input'):/input",
           '-v', "$(Join-Path $WorkRoot 'output'):/output")
    if (Test-Path $ModelsDir) { $a += @('-v', "${ModelsDir}:/app/models") }
    return $a
}

# 密钥注入：若存在 secret.env 就用 --env-file 传进容器（DEEPSEEK_API_KEY 等）
# 优先项目根目录的 ./secret.env（与 docker-compose.full.yml 一致），其次兼容老的 D:\mit-work\secret.env
function Secret-Args {
    foreach ($f in @((Join-Path $Root 'secret.env'), 'D:\mit-work\secret.env')) {
        if (Test-Path $f) { return @('--env-file', $f) }
    }
    return @()
}

function Write-NetTest {
    Ensure-Dirs
    $p = Join-Path $WorkRoot 'net_test.py'
    @'
import os, sys, time, urllib.request
keys = ("http_proxy", "https_proxy", "no_proxy", "hf_endpoint", "hf_home")
print("env:", {k: v for k, v in os.environ.items() if k.lower() in keys})
urls = ["https://hf-mirror.com", "https://huggingface.co", "https://github.com",
        "https://api.github.com", "https://frederik-uni.github.io"]
for u in urls:
    t = time.time()
    try:
        with urllib.request.urlopen(u, timeout=20) as r:
            print("OK   %-32s %s  %.1fs" % (u, r.status, time.time() - t))
    except Exception as e:
        print("FAIL %-32s %s: %s  %.1fs" % (u, type(e).__name__, e, time.time() - t))
'@ | Set-Content -Path $p -Encoding UTF8
    return $p
}

switch ($Action) {

    'probe' {
        Say "宿主机 GPU："
        & nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader
        Say "Docker："
        & docker version --format 'client={{.Client.Version}} server={{.Server.Version}}'
        $rt = (& docker info --format '{{json .Runtimes}}' | ConvertFrom-Json).PSObject.Properties.Name -join ', '
        Say "运行时：$rt"
        if ($rt -notmatch 'nvidia') { Warn "没有 nvidia-container-runtime，容器里用不了 GPU" }
        Say "已占用端口（注意 8010 / 5003 是否冲突）："
        & docker ps --format '{{.Names}} -> {{.Ports}}'
        Say "镜像站前缀：$(if ($RegistryMirror) { $RegistryMirror } else { '（直连）' })"
        Say "候选基镜像："
        $b = Resolve-Base $BaseImage
        if ($b) { Say "将使用：$b" } else { Warn "一个都取不到：多半是 registry 被墙，用 -RegistryMirror 换个镜像站" }
    }

    'gpu-test' {
        $cudaImg = Mirror 'nvidia/cuda:12.8.0-base-ubuntu22.04'
        Say "在容器里跑 nvidia-smi（首次会拉 ~150MB：$cudaImg）..."
        & docker run --rm --gpus all $cudaImg nvidia-smi
        if ($LASTEXITCODE -ne 0) { Die "容器拿不到 GPU —— 检查 Docker Desktop 是否启用 GPU、驱动是否够新" }
    }

    'net-test' {
        $p = Write-NetTest
        Say "容器内网络测试（走代理：$(-not $NoProxy)）..."
        $pyImg = Mirror 'python:3.12-slim'
        $a = @('run','--rm','-v',"$(Split-Path $p):/work",'--add-host','host.docker.internal:host-gateway') + (Env-Args) + (Secret-Args) + @($pyImg,'python','/work/net_test.py')
        & docker @a
        Say "如果全 FAIL：在 Clash 里打开“允许局域网连接”，或用 -NoProxy 重试"
    }

    'build' {
        $b = Resolve-Base $BaseImage
        if (-not $b) { Die "取不到任何可用的基镜像，先解决网络/代理（可试 -BuildProxy）" }
        Say "构建 $Image （基镜像 $b）"
        # -f 用绝对路径：Dockerfile 路径是相对“当前工作目录”解析的，不是相对构建上下文
        $ba = @('build','-f',(Join-Path $Root 'Dockerfile.cu128'),'--build-arg',"BASE_IMAGE=$b",'-t',$Image)
        if ($BuildProxy -and $ProxyUrl) {
            $ba += @('--build-arg',"HTTP_PROXY=$ProxyUrl",'--build-arg',"HTTPS_PROXY=$ProxyUrl")
            Say "构建期使用代理 $ProxyUrl"
        }
        $ba += $Root
        $env:DOCKER_BUILDKIT = '1'
        & docker @ba
        if ($LASTEXITCODE -ne 0) { Die "构建失败；若卡在下载依赖，加 -BuildProxy 再试" }
        Say "构建完成。下一步： .\mit-docker.ps1 up"
    }

    'up' {
        # 全栈（引擎 + MySQL + Redis + App 接口）编排在 docker-compose.full.yml；
        # 这里只起 engine 一个服务，免得两套东西抢同一个容器名 mit-engine。
        # 想一次起全套： .\mit-app.ps1 up
        Invoke-Quiet { & docker image inspect $Image }
        if ($LASTEXITCODE -ne 0) { Die "镜像 $Image 不存在，先跑： .\mit-docker.ps1 build" }
        $ef = Join-Path $Root 'app.env'
        $ca = @('compose','-f',(Join-Path $Root 'docker-compose.full.yml'))
        if (Test-Path $ef) { $ca += @('--env-file', $ef) }
        Invoke-Quiet { & docker @($ca + @('up','-d','engine')) }
        if ($LASTEXITCODE -ne 0) { Die "启动失败" }
        Start-Sleep -Seconds 3
        Say "Web UI / API : http://localhost:$WebPort"
        Say "结果目录      : $(Join-Path $WorkRoot 'output')  （容器内 /output）"
        Say "模型目录      : $ModelsDir  （容器内 /app/models）"
        Say "全栈（含 App 接口 8020）: .\mit-app.ps1 up"
        Warn "首次翻译会下载模型（1~2GB），日志里能看到进度： .\mit-docker.ps1 logs"
    }

    'api' {
        Say "以 API 模式（shared）前台运行，端口 $ApiHostPort ..."
        $a = @('run','--rm','-it','--gpus','all','-p',"${ApiHostPort}:5003",'--shm-size','2g') +
             (Mount-Args) + (Env-Args) + (Secret-Args) +
             @($Image,'shared','--host','0.0.0.0','--port','5003','--use-gpu')
        & docker @a
    }

    'cli' {
        $in  = if ($Input)  { (Resolve-Path $Input).Path }  else { Join-Path $WorkRoot 'input' }
        $out = if ($Output) { $Output } else { Join-Path $WorkRoot 'output' }
        if (-not (Test-Path $out)) { New-Item -ItemType Directory -Force -Path $out | Out-Null }
        $extra = @($ArgLine -split '\s+' | Where-Object { $_ })
        Say "批处理：$in  ->  $out"
        $a = @('run','--rm','-it','--gpus','all','--shm-size','2g','-v',"${in}:/input",'-v',"${out}:/output",'-v',"${WorkRoot}:/work") +
             @('-v',"${ModelsDir}:/app/models") + (Env-Args) + (Secret-Args) +
             @($Image,'local','-i','/input','-o','/output','--use-gpu') + $extra
        & docker @a
    }

    'down'    {
        # 由 compose 管理的容器就用 compose 停（别用 docker rm，会跟编排状态对不上）
        $ef = Join-Path $Root 'app.env'
        $ca = @('compose','-f',(Join-Path $Root 'docker-compose.full.yml'))
        if (Test-Path $ef) { $ca += @('--env-file', $ef) }
        Invoke-Quiet { & docker @($ca + @('stop','engine')) }
        Say "已停止 $Container"
    }
    'restart' {
        $ef = Join-Path $Root 'app.env'
        $ca = @('compose','-f',(Join-Path $Root 'docker-compose.full.yml'))
        if (Test-Path $ef) { $ca += @('--env-file', $ef) }
        Invoke-Quiet { & docker @($ca + @('restart','engine')) }
        Say "已重启 $Container"
    }
    'logs'    { & docker logs -f --tail 200 $Container }
    'status'  {
        & docker ps -a --filter "name=$Container" --format '{{.Names}}  {{.Status}}  {{.Ports}}'
        try { Say ("queue-size = " + (Invoke-RestMethod -Method Post "http://localhost:$WebPort/queue-size" -TimeoutSec 5)) } catch { Warn "服务还没起来或端口不对" }
    }
    'shell' {
        $a = @('run','--rm','-it','--gpus','all','--entrypoint','bash') + (Mount-Args) + (Env-Args) + (Secret-Args) + @($Image)
        & docker @a
    }
}
