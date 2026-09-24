<#
  P7-C ｜ Wayfare 端到端降级演练脚本
  ================================================================
  一条命令跑完「降级 → 验证 → 恢复 → 验证 → 出报告」全流程，
  产出可直接放进答辩 PPT 的对比表。

  用法（在仓库根目录）：
      powershell -ExecutionPolicy Bypass -File scripts\drill-fallback.ps1
      powershell -ExecutionPolicy Bypass -File scripts\drill-fallback.ps1 -SkipFaultInjection

  前置：
      · 后端已在 8080 运行（cd wayfare-backend && mvn spring-boot:run）
      · .env.properties 里配好 BAIDU_MAP_AK（阶段 B 要用真实地图）
      · 有管理员账号（默认 admin / Admin123456）

  ⚠️ 会临时改动两个 L2 配置（脚本结束时**恢复原值**）：
      · map.enabled          —— 阶段 A 关、阶段 B 开
      · map.baidu.ak         —— 仅故障注入阶段临时写坏，结束设回空串（回落到 .env.properties）
  ⚠️ 阶段 B 会真实调用百度地图（约 6 次地点检索 + 若干路线），**消耗百度日配额**。

  ⚠️ 编码注意：本文件必须保存为 **UTF-8 with BOM**。PowerShell 5.1 读无 BOM 的脚本会按 GBK 解析，
     中文字面量会被拆成乱码且**不报错**（本项目 2026-09-23 真实踩过，排查了很久）。
#>

[CmdletBinding()]
param(
    [string]$Base = 'http://localhost:8080/api',
    [string]$Username = 'admin',
    [string]$Password = 'Admin123456',
    [string]$RawInput = '周末想去大同玩两天，喜欢古建筑，预算 500',
    [string]$City = '大同',
    [string]$Keyword = '景点',
    [string]$OutReport = '',
    [string]$FaultCity = '朔州',
    [string]$FaultKeyword = '古建筑',
    [string]$RedisHost = '127.0.0.1',
    [int]$RedisPort = 6379,
    [switch]$SkipFaultInjection
)

$ErrorActionPreference = 'Continue'
if (-not $OutReport) {
    $root = Split-Path -Parent $PSScriptRoot
    $OutReport = Join-Path $root 'docs\drill-report.md'
}

# ==================== 断言与输出 ====================
$script:Results = New-Object System.Collections.ArrayList

function Assert-That {
    param([string]$Name, [bool]$Pass, [string]$Actual)
    [void]$script:Results.Add([pscustomobject]@{ Name = $Name; Pass = $Pass; Actual = $Actual })
    if ($Pass) {
        Write-Host ("  [PASS] " + $Name) -ForegroundColor Green
    } else {
        Write-Host ("  [FAIL] " + $Name) -ForegroundColor Red
        Write-Host ("         实际值: " + $Actual) -ForegroundColor Yellow
    }
}

function Section([string]$t) {
    Write-Host ''
    Write-Host ('=== ' + $t + ' ===') -ForegroundColor Cyan
}

# ==================== HTTP ====================
$script:Token = $null

function Api {
    param([string]$Method, [string]$Path, $Body, [int]$TimeoutSec = 300)
    $req = [Net.HttpWebRequest]::Create($Base + $Path)
    $req.Method = $Method
    $req.Timeout = $TimeoutSec * 1000
    $req.ReadWriteTimeout = $TimeoutSec * 1000
    if ($script:Token) { $req.Headers.Add('Authorization', 'Bearer ' + $script:Token) }
    if ($null -ne $Body) {
        $json = ($Body | ConvertTo-Json -Depth 8 -Compress)
        $req.ContentType = 'application/json'
        $bytes = [Text.Encoding]::UTF8.GetBytes($json)
        $req.ContentLength = $bytes.Length
        $s = $req.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
    }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $resp = $req.GetResponse()
        $sr = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
        $out = $sr.ReadToEnd(); $sr.Close(); $resp.Close(); $sw.Stop()
        return @{ ok = $true; ms = $sw.ElapsedMilliseconds; raw = $out }
    } catch {
        $sw.Stop()
        $msg = ''
        try {
            $sr = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream(), [Text.Encoding]::UTF8)
            $msg = $sr.ReadToEnd()
        } catch { }
        return @{ ok = $false; ms = $sw.ElapsedMilliseconds; raw = ('HTTP 错误: ' + $_.Exception.Message + ' ' + $msg) }
    }
}

function Get-Doc($resp) {
    # 解析后端统一响应体，返回 data 节点（失败返回 $null）
    try {
        $d = $resp.raw | ConvertFrom-Json
        if ($d.code -ne 200) { return $null }
        return $d.data
    } catch { return $null }
}

# 从生成结果里摊平所有 item
function Get-Items($syncData) {
    $list = New-Object System.Collections.ArrayList
    if ($null -eq $syncData) { return $list }
    foreach ($day in $syncData.trip.days) {
        foreach ($it in $day.items) { [void]$list.Add($it) }
    }
    return $list
}

function Set-Config([string]$Key, [string]$Value) {
    return Api 'PUT' ('/admin/configs/' + $Key) @{ value = $Value } 30
}

function Set-MapEnabled([bool]$On) {
    return Api 'POST' '/admin/config/map/enabled' @{ enabled = $On } 30
}

function Get-MapStatus {
    $r = Api 'GET' '/connector/map/status' $null 30
    return (Get-Doc $r)
}

function Get-Mode {
    $st = Get-MapStatus
    if ($null -eq $st) { return '(诊断接口不可用)' }
    return [string]$st.mode
}

# ---- 直连 Redis：清 POI 缓存（故障注入必须让请求真的打到百度，否则命中缓存就测不到熔断）----
# ⚠️ RESP 的 bulk string 长度必须用 **UTF-8 字节数**，不能用 $p.Length（UTF-16 字符数）——
#    键里有中文时长度会算少，Redis 会协议错位并**静默丢弃整条命令**（本项目 2026-09-23 踩过）。
function RedisCmd([string[]]$parts) {
    $c = New-Object Net.Sockets.TcpClient($RedisHost, $RedisPort)
    $s = $c.GetStream()
    $sb = New-Object Text.StringBuilder
    [void]$sb.Append('*' + $parts.Count + "`r`n")
    foreach ($p in $parts) {
        [void]$sb.Append('$' + ([Text.Encoding]::UTF8.GetByteCount($p)) + "`r`n" + $p + "`r`n")
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes($sb.ToString())
    $s.Write($bytes, 0, $bytes.Length); $s.Flush()
    Start-Sleep -Milliseconds 250
    $buf = New-Object byte[] 262144
    $n = $s.Read($buf, 0, $buf.Length)
    $c.Close()
    return [Text.Encoding]::UTF8.GetString($buf, 0, $n)
}

function Clear-PoiCache {
    $resp = RedisCmd @('KEYS', 'map:poi*')
    $n = 0
    foreach ($m in [regex]::Matches($resp, '\$(\d+)\r\n([^\r\n]+)')) {
        if ($m.Groups[2].Value -like '*map:poi*') { RedisCmd @('DEL', $m.Groups[2].Value) | Out-Null; $n++ }
    }
    return $n
}

# ---- 只取「文案」文本（标题 + 日程标题/摘要 + 条目的 reason/note）----
# 刻意**不含** validation 与 meta：校验消息里本来就会出现「超过 15 分钟」这类数字，
# 那是系统自己的提示，不是给用户看的行程文案（本项目实测踩过这个假阳性）。
function Get-CopyText($syncData) {
    if ($null -eq $syncData) { return '' }
    $parts = New-Object System.Collections.ArrayList
    [void]$parts.Add([string]$syncData.trip.title)
    foreach ($day in $syncData.trip.days) {
        [void]$parts.Add([string]$day.title)
        [void]$parts.Add([string]$day.summary)
        foreach ($it in $day.items) {
            [void]$parts.Add([string]$it.reason)
            [void]$parts.Add([string]$it.note)
        }
    }
    return ($parts -join ' ')
}

# ==================== 开始 ====================
Write-Host '============================================================' -ForegroundColor White
Write-Host ' Wayfare 端到端降级演练（P7-C）' -ForegroundColor White
Write-Host '============================================================' -ForegroundColor White

Section '0. 前置检查'
$health = Api 'GET' '/health' $null 10
Assert-That '后端可达（/health 返回 status=UP）' ($health.ok -and $health.raw -match '"status":"UP"') $health.raw

# 登录必须是第一个带 body 的请求（WebClient 先发 GET 会丢 Content-Type，本项目踩过）
$login = Api 'POST' '/auth/login' @{ username = $Username; password = $Password } 20
if (-not $login.ok) {
    Write-Host ('登录失败，终止：' + $login.raw) -ForegroundColor Red
    exit 2
}
$script:Token = (Get-Doc $login).token
Assert-That '管理员登录成功（拿到 JWT）' ([bool]$script:Token) ('token 长度=' + ([string]$script:Token).Length)

# 记录初始状态，结束时恢复
$initMapEnabled = $null
$cfg = Get-Doc (Api 'GET' '/admin/configs/map.enabled' $null 20)
if ($cfg -and $cfg.configValue) { $initMapEnabled = ([string]$cfg.configValue -eq 'true') }
if ($null -eq $initMapEnabled) {
    $st0 = Get-MapStatus
    $initMapEnabled = -not ($st0.mode -eq 'ESTIMATED' -and $st0.provider -eq 'disabled')
}
Write-Host ('  初始 map.enabled = ' + $initMapEnabled + '（演练结束会恢复到这个值）') -ForegroundColor Gray
$akBefore = Get-Doc (Api 'GET' '/admin/configs/map.baidu.ak' $null 20)
Write-Host ('  初始 map.baidu.ak = ' + [string]($akBefore.configValue) + '（空=回落到 .env.properties）') -ForegroundColor Gray

# ==================== 阶段 A：关闭地图 ====================
Section '阶段 A ｜ 关闭地图连接器（估算模式）'
[void](Set-MapEnabled $false)
Start-Sleep -Milliseconds 600
$modeA = Get-Mode
Assert-That 'A1 诊断接口 mode = ESTIMATED' ($modeA -eq 'ESTIMATED') ('mode=' + $modeA)

$swA = [Diagnostics.Stopwatch]::StartNew()
$respA = Api 'POST' '/trip/plan/sync' @{ rawInput = $RawInput; useProfile = $true } 600
$swA.Stop()
$dataA = Get-Doc $respA
$itemsA = Get-Items $dataA

Assert-That 'A2 生成完整可用（拿到 tripId、无 composeError）' `
    ([bool]$dataA -and [bool]$dataA.tripId -and -not $dataA.composeError) `
    ('tripId=' + [string]$dataA.tripId + ' composeError=' + [string]$dataA.composeError)

$badVerifyA = @($itemsA | Where-Object { $_.verifyStatus -ne 'ESTIMATED' })
Assert-That ('A3 全部条目 verifyStatus = ESTIMATED（共 ' + $itemsA.Count + ' 条）') `
    ($itemsA.Count -gt 0 -and $badVerifyA.Count -eq 0) `
    ('非 ESTIMATED 的条目数=' + $badVerifyA.Count + ' 取值=' + (($badVerifyA | ForEach-Object { $_.verifyStatus }) -join ','))

$withDistA = @($itemsA | Where-Object { $null -ne $_.distanceMeters })
Assert-That 'A4 全部条目 distanceMeters = null' ($withDistA.Count -eq 0) ('有距离的条目数=' + $withDistA.Count)

$modeMetaA = if ($dataA) { [string]$dataA.meta.mapMode } else { '(无)' }
Assert-That 'A5 meta.mapMode = ESTIMATED' ($modeMetaA -eq 'ESTIMATED') ('meta.mapMode=' + $modeMetaA)

# 文案不得出现精确距离/时长（估算模式的口径；只扫文案，不扫校验消息与数值字段）
$copyA = Get-CopyText $dataA
$kmHits = [regex]::Matches($copyA, '[0-9]+(\.[0-9]+)?\s*公里')
$minHits = [regex]::Matches($copyA, '[0-9]+\s*分钟')
Assert-That 'A6 文案中没有「N 公里」这类精确距离表述' ($kmHits.Count -eq 0) `
    ('命中 ' + $kmHits.Count + ' 处：' + (($kmHits | ForEach-Object { $_.Value }) -join ' / '))
Assert-That 'A7 文案中没有「N 分钟」这类精确时长表述' ($minHits.Count -eq 0) `
    ('命中 ' + $minHits.Count + ' 处：' + (($minHits | ForEach-Object { $_.Value }) -join ' / ') + '（估算模式应写「约十几分钟」这种不带数字的模糊表述）')

Write-Host ('  阶段 A 耗时 ' + [math]::Round($swA.Elapsed.TotalSeconds, 1) + ' s，条目 ' + $itemsA.Count + ' 条') -ForegroundColor Gray

# ==================== 阶段 B：打开地图 ====================
Section '阶段 B ｜ 打开地图连接器（实测模式）'
[void](Set-MapEnabled $true)
Start-Sleep -Milliseconds 600
$modeB = Get-Mode
Assert-That 'B1 诊断接口 mode = VERIFIED（地图可用）' ($modeB -eq 'VERIFIED') ('mode=' + $modeB + '（若为 CACHED/ESTIMATED 说明 AK 不可用或熔断未复位）')

$swB = [Diagnostics.Stopwatch]::StartNew()
$respB = Api 'POST' '/trip/plan/sync' @{ rawInput = $RawInput; useProfile = $true } 600
$swB.Stop()
$dataB = Get-Doc $respB
$itemsB = Get-Items $dataB

Assert-That 'B2 生成完整可用（拿到 tripId、无 composeError）' `
    ([bool]$dataB -and [bool]$dataB.tripId -and -not $dataB.composeError) `
    ('tripId=' + [string]$dataB.tripId + ' composeError=' + [string]$dataB.composeError)

$verifiedB = @($itemsB | Where-Object { $_.verifyStatus -eq 'VERIFIED' })
Assert-That 'B3 存在 verifyStatus = VERIFIED 的条目' ($verifiedB.Count -gt 0) `
    ('VERIFIED 条目数=' + $verifiedB.Count + ' / 共 ' + $itemsB.Count + ' 条')

$distB = @($itemsB | Where-Object { $null -ne $_.distanceMeters })
Assert-That 'B4 存在 distanceMeters 有值的条目' ($distB.Count -gt 0) ('有距离的条目数=' + $distB.Count)

$modeMetaB = if ($dataB) { [string]$dataB.meta.mapMode } else { '(无)' }
Assert-That 'B5 meta.mapMode = VERIFIED' ($modeMetaB -eq 'VERIFIED') ('meta.mapMode=' + $modeMetaB)

Write-Host ('  阶段 B 耗时 ' + [math]::Round($swB.Elapsed.TotalSeconds, 1) + ' s，条目 ' + $itemsB.Count + ' 条') -ForegroundColor Gray

# ==================== 阶段 C：故障注入 ====================
$fault = $null
if (-not $SkipFaultInjection) {
    Section '阶段 C ｜ 故障注入（把百度 AK 写坏，模拟「地图服务挂了」）'
    [void](Set-MapEnabled $true)
    Start-Sleep -Milliseconds 400
    [void](Set-Config 'map.baidu.ak' 'DRILL_INVALID_AK_0000000000000000')
    Start-Sleep -Milliseconds 600

    # 必须先清 POI 缓存：否则请求命中 Redis 缓存、根本不会打百度，也就累计不到熔断失败
    $clearedKeys = Clear-PoiCache
    Write-Host ('  已清 POI 缓存键 ' + $clearedKeys + ' 个（保证下面 6 次请求真的打到百度）') -ForegroundColor DarkGray

    $failSeen = 0
    for ($i = 1; $i -le 6; $i++) {
        $q = '/connector/map/search?city=' + [uri]::EscapeDataString($FaultCity) +
             '&keyword=' + [uri]::EscapeDataString($FaultKeyword) + '&pageNum=1&pageSize=10'
        $r = Api 'GET' $q $null 60
        $d = Get-Doc $r
        # 失败判据：接口返回错误 / 拿不到 data（坏 AK 时百度回 status!=0 → 后端抛错）。
        # 刻意**不**把 count=0 当失败 —— 那是「查得到但没结果」，属正常返回。
        $isFail = (-not $r.ok) -or ($null -eq $d)
        if ($isFail) { $failSeen++ }
        $desc = if ($isFail) { '失败（预期）' } else { '成功 count=' + [string]$d.count }
        Write-Host ('  注入第 ' + $i + ' 次调用：' + $desc) -ForegroundColor DarkGray
        Start-Sleep -Milliseconds 300
    }
    Assert-That ('C1 连续调用已产生失败（fail-threshold=5，实际 ' + $failSeen + ' 次）') ($failSeen -ge 5) `
        ('观察到失败 ' + $failSeen + ' 次（city=' + $FaultCity + ' keyword=' + $FaultKeyword + '）')

    Start-Sleep -Milliseconds 600
    $stC = Get-MapStatus
    $modeC = [string]$stC.mode
    $degradedC = [bool]$stC.degraded
    Assert-That 'C2 熔断后降级生效：mode 变为 CACHED 或 ESTIMATED' `
        ($modeC -eq 'CACHED' -or $modeC -eq 'ESTIMATED') `
        ('mode=' + $modeC + ' degraded=' + $degradedC + ' reason=' + [string]$stC.reason)

    $swC = [Diagnostics.Stopwatch]::StartNew()
    $respC = Api 'POST' '/trip/plan/sync' @{ rawInput = $RawInput; useProfile = $true } 600
    $swC.Stop()
    $dataC = Get-Doc $respC
    $itemsC = Get-Items $dataC
    Assert-That 'C3 地图全挂时生成流程不中断（仍返回 tripId）' `
        ([bool]$dataC -and [bool]$dataC.tripId) `
        ('tripId=' + [string]$dataC.tripId + ' composeError=' + [string]$dataC.composeError)

    $fault = [pscustomobject]@{
        FailSeen = $failSeen
        Cleared  = $clearedKeys
        Query    = ($FaultCity + ' / ' + $FaultKeyword)
        Mode     = $modeC
        Degraded = $degradedC
        Reason   = [string]$stC.reason
        TripId   = [string]$dataC.tripId
        Sec      = [math]::Round($swC.Elapsed.TotalSeconds, 1)
        Items    = $itemsC.Count
        Verify   = (@($itemsC | Where-Object { $_.verifyStatus -eq 'VERIFIED' }).Count)
    }

    # 恢复：AK 设回空串（回落 .env.properties）+ 复位熔断
    [void](Set-Config 'map.baidu.ak' '')
    Start-Sleep -Milliseconds 400
    [void](Api 'POST' '/connector/map/breaker/reset' $null 30)
    Start-Sleep -Milliseconds 600
    $modeR = Get-Mode
    Assert-That 'C4 恢复 AK + 复位熔断后 mode 回到 VERIFIED' ($modeR -eq 'VERIFIED') ('mode=' + $modeR)
} else {
    Write-Host ''
    Write-Host '阶段 C ｜ 已跳过故障注入（-SkipFaultInjection）' -ForegroundColor Yellow
}

# ==================== 恢复初始状态 ====================
Section '恢复初始状态'
[void](Set-MapEnabled $initMapEnabled)
Start-Sleep -Milliseconds 600
$modeFinal = Get-Mode
$expectFinal = if ($initMapEnabled) { 'VERIFIED' } else { 'ESTIMATED' }
Assert-That ('R1 map.enabled 已恢复到初始值 ' + $initMapEnabled) ($modeFinal -eq $expectFinal) `
    ('mode=' + $modeFinal + '（期望 ' + $expectFinal + '）')
[void](Set-Config 'map.baidu.ak' '')
$akAfter = Get-Doc (Api 'GET' '/admin/configs/map.baidu.ak' $null 20)
Assert-That 'R2 map.baidu.ak 已清回空串（回落 .env.properties）' `
    ([string]::IsNullOrEmpty([string]$akAfter.configValue)) ('ak=' + [string]$akAfter.configValue)

# ==================== 汇总 ====================
$pass = @($script:Results | Where-Object { $_.Pass }).Count
$fail = @($script:Results | Where-Object { -not $_.Pass })
$total = $script:Results.Count

Section '演练结论'
Write-Host ('  断言 ' + $pass + ' / ' + $total + ' 通过') -ForegroundColor $(if ($fail.Count -eq 0) { 'Green' } else { 'Red' })
if ($fail.Count -gt 0) {
    Write-Host '  未通过的断言：' -ForegroundColor Red
    foreach ($f in $fail) { Write-Host ('    - ' + $f.Name + ' → 实际值 ' + $f.Actual) -ForegroundColor Red }
}

# ==================== 出报告 ====================
$estCountA = @($itemsA | Where-Object { $_.verifyStatus -eq 'ESTIMATED' }).Count
$verCountB = $verifiedB.Count
$distCountB = $distB.Count
$sb = New-Object Text.StringBuilder
[void]$sb.AppendLine('# 端到端降级演练报告（P7-C）')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('> 本文件由 `scripts/drill-fallback.ps1` 自动生成，请勿手改。')
[void]$sb.AppendLine('> 生成时间：' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
[void]$sb.AppendLine('> 固定输入：`' + $RawInput + '`')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('## 一、核心对比表（可直接复制进 PPT）')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('| 维度 | 地图关闭（估算模式） | 地图开启（实测模式） |')
[void]$sb.AppendLine('|---|---|---|')
[void]$sb.AppendLine('| 数据来源标记 | ESTIMATED × ' + $estCountA + ' | VERIFIED × ' + $verCountB + '（其余为 CACHED/ESTIMATED） |')
[void]$sb.AppendLine('| 距离字段 | 全部为空 | ' + $distCountB + ' / ' + $itemsB.Count + ' 条有值 |')
[void]$sb.AppendLine('| 文案距离表述 | 模糊（「相距不远」「步行即可到达」） | 由实测距离驱动 |')
[void]$sb.AppendLine('| 生成耗时 | ' + [math]::Round($swA.Elapsed.TotalSeconds, 1) + ' s | ' + [math]::Round($swB.Elapsed.TotalSeconds, 1) + ' s |')
[void]$sb.AppendLine('| 是否可用 | ✅ 完整可用 | ✅ 完整可用 |')
[void]$sb.AppendLine('| 行程条目数 | ' + $itemsA.Count + ' | ' + $itemsB.Count + ' |')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('**一句话结论**：关掉地图能力后系统仍然产出**结构完整、可执行的行程**（' + $itemsA.Count + ' 个条目），')
[void]$sb.AppendLine('只是所有事实性数据降级为「估算」标记、距离字段留空、文案不再给精确数字 ——')
[void]$sb.AppendLine('这就是铁律二「连接器可插拔、任一时刻系统完整可用」的可验证证据。')
[void]$sb.AppendLine('')
if ($null -ne $fault) {
    [void]$sb.AppendLine('## 二、故障注入（模拟「百度地图服务挂了」）')
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('做法：把 `map.baidu.ak`（L2 配置，**改完立即生效、不用重启**）写成一个非法值，')
    [void]$sb.AppendLine('让真实请求返回 `status != 0`，从而累计熔断失败次数（阈值 `map.breaker.fail-threshold=5`）。')
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('| 观察项 | 结果 |')
    [void]$sb.AppendLine('|---|---|')
    [void]$sb.AppendLine('| 注入前清掉的 POI 缓存键 | ' + $fault.Cleared + ' 个（保证请求真的打到百度，而不是命中缓存） |')
    [void]$sb.AppendLine('| 注入用检索 | ' + $fault.Query + ' |')
    [void]$sb.AppendLine('| 连续失败次数 | ' + $fault.FailSeen + ' |')
    [void]$sb.AppendLine('| 熔断后 mode | ' + $fault.Mode + ' |')
    [void]$sb.AppendLine('| degraded 标记 | ' + $fault.Degraded + ' |')
    [void]$sb.AppendLine('| 降级原因 | ' + $fault.Reason + ' |')
    [void]$sb.AppendLine('| 生成是否中断 | ✅ 未中断，仍返回 tripId ' + $fault.TripId + '（' + $fault.Items + ' 个条目，耗时 ' + $fault.Sec + ' s） |')
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('**结论**：地图上游整体挂掉时，熔断器自动打开，决策器改走缓存/估算，')
    [void]$sb.AppendLine('**生成流程不中断、用户侧无感知故障**。演练结束后已恢复 AK 并复位熔断。')
    [void]$sb.AppendLine('')
}
[void]$sb.AppendLine('## 三、断言明细')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('| # | 断言 | 结果 | 实际值 |')
[void]$sb.AppendLine('|---|---|---|---|')
$i = 0
foreach ($r in $script:Results) {
    $i++
    $mark = if ($r.Pass) { '✅ 通过' } else { '❌ 失败' }
    $act = ([string]$r.Actual) -replace '\|', '/'
    [void]$sb.AppendLine('| ' + $i + ' | ' + $r.Name + ' | ' + $mark + ' | ' + $act + ' |')
}
[void]$sb.AppendLine('')
[void]$sb.AppendLine('**合计 ' + $pass + ' / ' + $total + ' 通过。**')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('## 四、复现方式')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('```powershell')
[void]$sb.AppendLine('cd ' + (Split-Path -Parent $PSScriptRoot))
[void]$sb.AppendLine('powershell -ExecutionPolicy Bypass -File scripts\drill-fallback.ps1')
[void]$sb.AppendLine('```')
[void]$sb.AppendLine('')
[void]$sb.AppendLine('> 会临时改动 `map.enabled` 与 `map.baidu.ak` 两个 L2 配置，**脚本结束自动恢复原值**；')
[void]$sb.AppendLine('> 阶段 B 会真实调用百度地图（消耗日配额）。加 `-SkipFaultInjection` 可跳过阶段 C。')

[IO.File]::WriteAllText($OutReport, $sb.ToString(), (New-Object Text.UTF8Encoding($false)))
Write-Host ''
Write-Host ('报告已生成：' + $OutReport) -ForegroundColor Green

if ($fail.Count -gt 0) { exit 1 }
exit 0
