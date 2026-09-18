# ============================================================
#  mit 全栈 Docker 助手（引擎 + MySQL + Redis + App 接口）
#
#    .\mit-app.ps1 up          构建需要的镜像并按依赖顺序起全部 4 个容器
#    .\mit-app.ps1 status      健康检查（引擎 / DB / Redis / App 接口）
#    .\mit-app.ps1 build       只构建镜像（引擎 + App 接口），不重启
#    .\mit-app.ps1 test        用测试图跑一遍"一页进 → 译文图出"（含缓存验证）
#    .\mit-app.ps1 logs        跟 App 接口日志
#    .\mit-app.ps1 engine-logs 跟引擎日志
#    .\mit-app.ps1 ps          容器列表
#    .\mit-app.ps1 down        停掉全部
#    .\mit-app.ps1 restart     重启 App 接口
#    .\mit-app.ps1 engine-restart  重启引擎（会清掉引擎里的共享实例状态，模型需重新加载）
#    .\mit-app.ps1 sql / redis 进 MySQL / Redis 命令行
#
#  实际编排文件是 docker-compose.full.yml（docker-compose.app.yml 只是 include 它）。
#  引擎单独的操作（probe / gpu-test / net-test / cli / shell / api）仍走 .\mit-docker.ps1。
# ============================================================
param(
    [Parameter(Position = 0)]
    [ValidateSet('up','down','restart','engine-restart','build','logs','engine-logs','status','test','sql','redis','ps')]
    [string]$Action = 'up',

    [int]$Port = 8020,
    [string]$Image = '',              # 留空 = <仓库>\_runtime\work\input\test_page.png
    [string]$BookId = 'test-book',
    [int]$PageIndex = 1,
    [string]$ExtraConfig = '',        # 例如 '{"translator":{"translator":"sugoi","target_lang":"ENG"}}'
    [switch]$Force
)

$ErrorActionPreference = 'Continue'
$Root = $PSScriptRoot
$Compose = Join-Path $Root 'docker-compose.full.yml'
$EnvFile = Join-Path $Root 'app.env'      # 账号密码，已加入 .gitignore
$Base = "http://127.0.0.1:$Port"
$DbPass = 'Alexmercer2000@'

# 测试图默认取项目内的工作目录（与 compose 的 _runtime\work\input 是同一处）
if (-not $Image) { $Image = Join-Path $Root '_runtime\work\input\test_page.png' }

function Say($m)  { Write-Host "[mit] $m" -ForegroundColor Cyan }
function Warn($m) { Write-Host "[mit] $m" -ForegroundColor Yellow }

function Compose-Args {
    $a = @('compose', '-f', $Compose)
    if (Test-Path $EnvFile) { $a += @('--env-file', $EnvFile) }
    return $a
}

switch ($Action) {
    'up'      { Set-Location $Root; & docker @((Compose-Args) + @('up','-d','--build')); Say "App API: $Base/docs   引擎/网页端: http://127.0.0.1:8010" }
    'down'    { Set-Location $Root; & docker @((Compose-Args) + @('down')) }
    'restart' { Set-Location $Root; & docker @((Compose-Args) + @('restart','app-api')) }
    'engine-restart' { Set-Location $Root; & docker @((Compose-Args) + @('restart','engine')) }
    'build'   { Set-Location $Root; & docker @((Compose-Args) + @('build')) }
    'ps'      { Set-Location $Root; & docker @((Compose-Args) + @('ps')) }
    'logs'    { & docker logs -f --tail 200 mit-app-api }
    'engine-logs' { & docker logs -f --tail 200 mit-engine }
    'sql'     { & docker exec -it mit-app-db mysql -umit "-p$DbPass" mit }
    'redis'   { & docker exec -it mit-app-redis redis-cli -a $DbPass }

    'status' {
        Say "健康检查 $Base/v1/health"
        & curl.exe -s --noproxy "*" --max-time 20 "$Base/v1/health"
        ""
        Say "引擎网页端 / API: http://127.0.0.1:8010  （引擎队列长度）"
        & curl.exe -s --noproxy "*" --max-time 20 -X POST "http://127.0.0.1:8010/queue-size"
        ""
        Set-Location $Root; & docker @((Compose-Args) + @('ps','--format','table {{.Name}}\t{{.Service}}\t{{.Status}}\t{{.Ports}}'))
    }

    'test' {
        if (-not (Test-Path $Image)) { Warn "找不到测试图：$Image"; break }
        Say "1) 健康检查"
        & curl.exe -s --noproxy "*" --max-time 20 "$Base/v1/health" | Out-Host

        $args = @('-s','--noproxy','*','--max-time','900','-X','POST',"$Base/v1/pages/translate",
                  '-F',"image=@$Image",'-F',"book_id=$BookId",'-F',"page_index=$PageIndex")
        if ($ExtraConfig) { $args += @('-F', "config=$ExtraConfig") }
        if ($Force) { $args += @('-F','force=true') }

        Say "2) 提交单页翻译（book_id=$BookId page_index=$PageIndex）—— 首次要跑管线，约 5~30 秒"
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $resp = & curl.exe @args
        $sw.Stop()
        Say ("   耗时 {0:N1}s" -f $sw.Elapsed.TotalSeconds)

        try {
            $j = ($resp -join "`n") | ConvertFrom-Json
            if ($j.detail -and -not $j.page_id) { Warn "失败：$($j.detail)"; break }
            Say "   page_id=$($j.page_id)  cached=$($j.cached)  context_used=$($j.context_used)  elapsed_ms=$($j.elapsed_ms)"
            foreach ($b in $j.blocks) {
                "     [$($b.bbox -join ',')]  $($b.src)  =>  $($b.dst)"
            }

            Say "3) 取译文图"
            $out = Join-Path (Split-Path $Image) 'app_api_translated.png'
            & curl.exe -s --noproxy "*" --max-time 60 -o $out "$Base/v1/pages/$($j.page_id)/image"
            if (Test-Path $out) { Say "   已保存：$out  ($((Get-Item $out).Length) 字节)" }

            Say "4) 同样的请求再发一次（验证 L1 结果缓存）"
            $j2 = ((& curl.exe @args) -join "`n") | ConvertFrom-Json
            Say "   cached=$($j2.cached)  elapsed_ms=$($j2.elapsed_ms)   ← 命中缓存应为 true 且几毫秒"
        } catch {
            Warn "返回内容解析失败，原文："
            $resp | Out-Host
        }
    }
}
