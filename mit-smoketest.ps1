# ============================================================
#  冒烟测试：确认 Docker 里的服务真的能翻译
#    .\mit-smoketest.ps1                       # 用默认配置（sugoi 离线日→英）
#    .\mit-smoketest.ps1 -BaseUrl http://localhost:8010
#  会输出：结构化 JSON（坐标/原文/译文）落到 output\smoke_result.json，
#          译文图落到 output\test_page_translated.png
# ============================================================
param(
    [string]$BaseUrl = 'http://localhost:8010',
    [string]$Image   = '',      # 留空 = <仓库>\_runtime\work\input\test_page.png
    [string]$OutDir  = '',      # 留空 = <仓库>\_runtime\work\output
    [string]$ConfigFile = '',
    [int]$TimeoutSec = 1800
)

$ErrorActionPreference = 'Continue'
function Say  ($m) { Write-Host "[smoke] $m" -ForegroundColor Cyan }
function Warn ($m) { Write-Host "[smoke] $m" -ForegroundColor Yellow }

# 默认落在项目内的工作目录（与 docker-compose.full.yml 的 _runtime\work 一致）
$Work = Join-Path $PSScriptRoot '_runtime\work'
if (-not $Image)  { $Image  = Join-Path $Work 'input\test_page.png' }
if (-not $OutDir) { $OutDir = Join-Path $Work 'output' }

if (-not (Test-Path $Image)) { Write-Host "找不到测试图：$Image" -ForegroundColor Red; exit 1 }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }

# ---- 默认配置：全离线，不需要任何 API key ----
if (-not $ConfigFile) {
    $ConfigFile = Join-Path $OutDir 'smoke_config.json'
    @'
{
  "translator": { "translator": "sugoi", "target_lang": "ENG" },
  "detector":   { "detector": "ctd" },
  "ocr":        { "ocr": "mocr" },
  "inpainter":  { "inpainter": "lama_large" },
  "render":     { "renderer": "default" }
}
'@ | ForEach-Object { [System.IO.File]::WriteAllText($ConfigFile, $_, (New-Object System.Text.UTF8Encoding($false))) }

    Warn "换成中文就把 translator 改成 sakura/qwen2（离线）或 deepseek/gemini/chatgpt（需 key），target_lang 改 CHS"
}

# ---- 1. 服务是否活着 ----
Say "探测服务：$BaseUrl/queue-size"
try {
    $q = Invoke-RestMethod -Method Post "$BaseUrl/queue-size" -TimeoutSec 10
    Say "服务在线，当前队列长度 = $q"
} catch {
    Write-Host "服务没起来：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "先跑 .\mit-docker.ps1 up，再看 .\mit-docker.ps1 logs"
    exit 1
}

# ---- 2. 结构化 JSON（原文 + 译文 + 坐标 + 抹字底图）----
$jsonOut = Join-Path $OutDir 'smoke_result.json'
Say "POST /translate/with-form/json  （首次会下载模型，可能要几分钟）"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
& curl.exe -sS --noproxy "*" --max-time $TimeoutSec -X POST "$BaseUrl/translate/with-form/json" `
    -F "image=@$Image" -F "config=<$ConfigFile" -o $jsonOut
$code = $LASTEXITCODE
$sw.Stop()
Say "curl exit=$code  用时 $([math]::Round($sw.Elapsed.TotalSeconds,1))s"

if (Test-Path $jsonOut) {
    $size = (Get-Item $jsonOut).Length
    Say "返回 JSON：$jsonOut ($size bytes)"
    try {
        $j = Get-Content $jsonOut -Raw -Encoding UTF8 | ConvertFrom-Json
        $n = @($j.translations).Count
        Say "识别到 $n 个文本块："
        foreach ($t in $j.translations) {
            $texts = @()
            foreach ($p in $t.text.PSObject.Properties) { $texts += "$($p.Name)=$($p.Value)" }
            "    [$($t.minX),$($t.minY)-$($t.maxX),$($t.maxY)] angle=$($t.angle)  " + ($texts -join ' | ')
        }
    } catch {
        Warn "JSON 解析失败（可能返回的是错误信息），原文前 300 字："
        (Get-Content $jsonOut -Raw -Encoding UTF8).Substring(0, [Math]::Min(300, (Get-Item $jsonOut).Length))
    }
}

# ---- 3. 译文图 ----
$pngOut = Join-Path $OutDir 'test_page_translated.png'
Say "POST /translate/with-form/image  ->  $pngOut"
& curl.exe -sS --noproxy "*" --max-time $TimeoutSec -X POST "$BaseUrl/translate/with-form/image" `
    -F "image=@$Image" -F "config=<$ConfigFile" -o $pngOut
Say "curl exit=$LASTEXITCODE"

if ((Test-Path $pngOut) -and (Get-Item $pngOut).Length -gt 1000) {
    Say "完成：译文图 $pngOut"
} else {
    Warn "译文图没生成或过小，检查 .\mit-docker.ps1 logs"
}
