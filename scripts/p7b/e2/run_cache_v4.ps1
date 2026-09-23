param(
    [string]$City = "大同",
    [string]$Keyword = "景点",
    [int]$Rounds = 20
)
$ErrorActionPreference = 'Continue'
$T = "$env:TEMP\wf_p7b\e2"
New-Item -ItemType Directory -Force -Path $T | Out-Null
$base = 'http://localhost:8080/api'

function RedisCmd([string[]]$parts) {
    $c = New-Object Net.Sockets.TcpClient('127.0.0.1', 6379)
    $s = $c.GetStream()
    $sb = New-Object Text.StringBuilder
    [void]$sb.Append('*' + $parts.Count + "`r`n")
    foreach ($p in $parts) {
        # 必须用 UTF-8 字节数，不能用 $p.Length（UTF-16 字符数）——
        # 键里有中文时长度会算少，Redis 会协议错位并丢弃整条命令（实测踩过）
        $byteLen = [Text.Encoding]::UTF8.GetByteCount($p)
        [void]$sb.Append('$' + $byteLen + "`r`n" + $p + "`r`n")
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes($sb.ToString())
    $s.Write($bytes, 0, $bytes.Length); $s.Flush()
    Start-Sleep -Milliseconds 250
    $buf = New-Object byte[] 262144
    $n = $s.Read($buf, 0, $buf.Length)
    $c.Close()
    return [Text.Encoding]::UTF8.GetString($buf, 0, $n)
}

function CountPoiKeys() {
    $resp = RedisCmd @('KEYS', 'map:poi*')
    $n = 0
    foreach ($m in [regex]::Matches($resp, '\$(\d+)\r\n([^\r\n]+)')) {
        if ($m.Groups[2].Value -like '*map:poi*') { $n++ }
    }
    return $n
}

function ClearPoiCache() {
    $resp = RedisCmd @('KEYS', 'map:poi*')
    $n = 0
    foreach ($m in [regex]::Matches($resp, '\$(\d+)\r\n([^\r\n]+)')) {
        if ($m.Groups[2].Value -like '*map:poi*') { RedisCmd @('DEL', $m.Groups[2].Value) | Out-Null; $n++ }
    }
    return $n
}

function HttpCall($method, $path, $bodyText, $token) {
    $req = [Net.HttpWebRequest]::Create("$base$path")
    $req.Method = $method
    $req.Timeout = 60000
    $req.ReadWriteTimeout = 60000
    if ($token) { $req.Headers.Add('Authorization', "Bearer $token") }
    if ($bodyText) {
        $req.ContentType = 'application/json'
        $b = [Text.Encoding]::UTF8.GetBytes($bodyText)
        $req.ContentLength = $b.Length
        $s = $req.GetRequestStream(); $s.Write($b, 0, $b.Length); $s.Close()
    }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $resp = $req.GetResponse()
        $sr = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
        $out = $sr.ReadToEnd(); $sr.Close(); $resp.Close(); $sw.Stop()
        return @{ ok = $true; ms = $sw.ElapsedMilliseconds; raw = $out }
    } catch {
        $sw.Stop()
        return @{ ok = $false; ms = $sw.ElapsedMilliseconds; raw = ('ERR ' + $_.Exception.Message) }
    }
}

$log = "$T\cache_run.txt"
Set-Content -Path $log -Value "=== P7-B item2 POI cache cold/warm (single query, $Rounds rounds), start $(Get-Date -Format o) ===" -Encoding UTF8
$rows = "$T\cache_runs.tsv"
"round,city,keyword,coldMs,coldMode,coldCount,warmMs,warmMode,warmCount" | Set-Content -Path $rows -Encoding UTF8

$login = HttpCall 'POST' '/auth/login' '{"username":"admin","password":"Admin123456"}' $null
if (-not $login.ok) { Add-Content -Path $log -Value ("ABORT login: " + $login.raw) -Encoding UTF8; exit 2 }
$tok = ($login.raw | ConvertFrom-Json).data.token
if (-not $tok) { Add-Content -Path $log -Value "ABORT: token null" -Encoding UTF8; exit 2 }
Add-Content -Path $log -Value "login ok tokenLen=$($tok.Length)" -Encoding UTF8

$probe = HttpCall 'GET' '/connector/map/status' $null $tok
if (-not $probe.ok) { Add-Content -Path $log -Value ("ABORT probe: " + $probe.raw) -Encoding UTF8; exit 2 }

$en = HttpCall 'POST' '/admin/config/map/enabled' '{"enabled":true}' $tok
Add-Content -Path $log -Value ("map enabled -> ok=" + $en.ok) -Encoding UTF8
Start-Sleep -Seconds 1
$cleared0 = ClearPoiCache
Add-Content -Path $log -Value "redis map:poi* cleared before run = $cleared0 ; remaining = $(CountPoiKeys)" -Encoding UTF8

$q = '/connector/map/search?city=' + [uri]::EscapeDataString($City) + '&keyword=' + [uri]::EscapeDataString($Keyword) + '&pageNum=1&pageSize=10'
$abort = $false
for ($i = 1; $i -le $Rounds; $i++) {
    $cleared = ClearPoiCache
    $afterClear = CountPoiKeys
    $cold = HttpCall 'GET' $q $null $tok
    $warm = HttpCall 'GET' $q $null $tok
    $cm = ''; $cc = ''; $wm = ''; $wc = ''
    try { $j = $cold.raw | ConvertFrom-Json; $cm = $j.data.mode; $cc = $j.data.count } catch {}
    try { $j2 = $warm.raw | ConvertFrom-Json; $wm = $j2.data.mode; $wc = $j2.data.count } catch {}
    "$i,$City,$Keyword,$($cold.ms),$cm,$cc,$($warm.ms),$wm,$wc" | Add-Content -Path $rows -Encoding UTF8
    Add-Content -Path $log -Value ("[{0}] cleared={1} afterClear={2} cold={3}ms({4},n={5}) warm={6}ms({7},n={8})" -f $i, $cleared, $afterClear, $cold.ms, $cm, $cc, $warm.ms, $wm, $wc) -Encoding UTF8
    if ($i -eq 1 -and ("$cc" -eq '0' -or "$cc" -eq '')) {
        Add-Content -Path $log -Value ("ABORT: first cold gave no data. raw=" + $cold.raw.Substring(0, [Math]::Min(300, $cold.raw.Length))) -Encoding UTF8
        $abort = $true
        break
    }
    Start-Sleep -Milliseconds 400
}

$off = HttpCall 'POST' '/admin/config/map/enabled' '{"enabled":false}' $tok
Add-Content -Path $log -Value ("map enabled=false (restored) ok=" + $off.ok) -Encoding UTF8
$keysAfter = RedisCmd @('KEYS', 'map:poi*')
$nAfter = 0
foreach ($m in [regex]::Matches($keysAfter, '\$(\d+)\r\n([^\r\n]+)')) { if ($m.Groups[2].Value -like '*map:poi*') { $nAfter++ } }
Add-Content -Path $log -Value "redis map:poi* keys left = $nAfter" -Encoding UTF8
Add-Content -Path $log -Value "abort=$abort" -Encoding UTF8
Add-Content -Path $log -Value "=== all done $(Get-Date -Format o) ===" -Encoding UTF8
