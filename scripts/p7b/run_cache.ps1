$ErrorActionPreference = 'Continue'
$T = "$env:TEMP\wf_p7b\b"
New-Item -ItemType Directory -Force -Path $T | Out-Null
$base = 'http://localhost:8080/api'

$w = New-Object Net.WebClient
$w.Encoding = [Text.Encoding]::UTF8
$w.Headers.Add('Content-Type', 'application/json')
$r = $w.UploadString("$base/auth/login", 'POST', '{"username":"admin","password":"Admin123456"}')
$tok = ($r | ConvertFrom-Json).data.token

$log = "$T\b_run.txt"
Set-Content -Path $log -Value "=== P7-B item2 POI cache cold/warm, start $(Get-Date -Format o) ===" -Encoding UTF8

function GetJson($path) {
    $req = [Net.HttpWebRequest]::Create("$base$path")
    $req.Method = 'GET'
    $req.Timeout = 60000
    $req.Headers.Add('Authorization', "Bearer $tok")
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

function Post($path, $bodyText) {
    $req = [Net.HttpWebRequest]::Create("$base$path")
    $req.Method = 'POST'
    $req.Timeout = 60000
    $req.ContentType = 'application/json'
    $req.Headers.Add('Authorization', "Bearer $tok")
    $b = [Text.Encoding]::UTF8.GetBytes($bodyText)
    $req.ContentLength = $b.Length
    $s = $req.GetRequestStream(); $s.Write($b, 0, $b.Length); $s.Close()
    $resp = $req.GetResponse()
    $sr = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
    $out = $sr.ReadToEnd(); $sr.Close(); $resp.Close()
    return $out
}

function RedisCmd([string[]]$parts) {
    $c = New-Object Net.Sockets.TcpClient('127.0.0.1', 6379)
    $s = $c.GetStream()
    $sb = New-Object Text.StringBuilder
    [void]$sb.Append('*' + $parts.Count + "`r`n")
    foreach ($p in $parts) { [void]$sb.Append('$' + $p.Length + "`r`n" + $p + "`r`n") }
    $bytes = [Text.Encoding]::UTF8.GetBytes($sb.ToString())
    $s.Write($bytes, 0, $bytes.Length); $s.Flush()
    Start-Sleep -Milliseconds 400
    $buf = New-Object byte[] 65536
    $n = $s.Read($buf, 0, $buf.Length)
    $c.Close()
    return [Text.Encoding]::UTF8.GetString($buf, 0, $n)
}

# ---- 1. 开地图 ----
Post '/admin/config/map/enabled' '{"enabled":true}' | Out-Null
Start-Sleep -Seconds 1
Add-Content -Path $log -Value "map enabled=true" -Encoding UTF8

# ---- 2. 清掉 POI 检索缓存（只删 map:poi* 键，不动别的）----
$keysResp = RedisCmd @('KEYS', 'map:poi*')
$keys = @()
foreach ($m in [regex]::Matches($keysResp, '\$(\d+)\r\n([^\r\n]+)')) {
    $key = $m.Groups[2].Value
    if ($key -notlike '*map:poi*') { continue }
    $keys += $key
}
$deleted = 0
foreach ($k in $keys) {
    $resp = RedisCmd @('DEL', $k)
    if ($resp -match ':\d') { $deleted++ }
}
Add-Content -Path $log -Value "redis keys matched=$($keys.Count) deleted=$deleted" -Encoding UTF8

# ---- 3. 冷/热各 20 次 ----
$pairsJson = [IO.File]::ReadAllText("$env:TEMP\wf_p7b\poi_pairs.json", [Text.Encoding]::UTF8)
$pairs = $pairsJson | ConvertFrom-Json
$rows = "$T\cache_runs.tsv"
"idx,city,keyword,coldMs,coldMode,coldCount,warmMs,warmMode,warmCount" | Set-Content -Path $rows -Encoding UTF8

$i = 0
foreach ($p in $pairs) {
    $i++
    $q = '/connector/map/search?city=' + [uri]::EscapeDataString([string]$p.city) + '&keyword=' + [uri]::EscapeDataString([string]$p.keyword) + '&pageNum=1&pageSize=10'
    $cold = GetJson $q
    $warm = GetJson $q
    $cm = ''; $cc = ''; $wm = ''; $wc = ''
    try { $j = $cold.raw | ConvertFrom-Json; $cm = $j.data.mode; $cc = $j.data.count } catch {}
    try { $j2 = $warm.raw | ConvertFrom-Json; $wm = $j2.data.mode; $wc = $j2.data.count } catch {}
    "$i,$($p.city),$($p.keyword),$($cold.ms),$cm,$cc,$($warm.ms),$wm,$wc" | Add-Content -Path $rows -Encoding UTF8
    Add-Content -Path $log -Value ("[{0}] {1}/{2} cold={3}ms({4},n={5}) warm={6}ms({7},n={8})" -f $i, $p.city, $p.keyword, $cold.ms, $cm, $cc, $warm.ms, $wm, $wc) -Encoding UTF8
    Start-Sleep -Milliseconds 300
}

# ---- 4. 关地图（用完立刻关）----
Post '/admin/config/map/enabled' '{"enabled":false}' | Out-Null
Add-Content -Path $log -Value "map enabled=false (restored)" -Encoding UTF8
Add-Content -Path $log -Value "=== all done $(Get-Date -Format o) ===" -Encoding UTF8