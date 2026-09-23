$ErrorActionPreference = 'Continue'
$T = "$env:TEMP\wf_p7b\e2"
New-Item -ItemType Directory -Force -Path $T | Out-Null
$base = 'http://localhost:8080/api'

function RedisCmd([string[]]$parts) {
    $c = New-Object Net.Sockets.TcpClient('127.0.0.1', 6379)
    $s = $c.GetStream()
    $sb = New-Object Text.StringBuilder
    [void]$sb.Append('*' + $parts.Count + "`r`n")
    foreach ($p in $parts) { [void]$sb.Append('$' + $p.Length + "`r`n" + $p + "`r`n") }
    $bytes = [Text.Encoding]::UTF8.GetBytes($sb.ToString())
    $s.Write($bytes, 0, $bytes.Length); $s.Flush()
    Start-Sleep -Milliseconds 300
    $buf = New-Object byte[] 65536
    $n = $s.Read($buf, 0, $buf.Length)
    $c.Close()
    return [Text.Encoding]::UTF8.GetString($buf, 0, $n)
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
        $msg = ''
        try { $sr = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream(), [Text.Encoding]::UTF8); $msg = $sr.ReadToEnd() } catch {}
        return @{ ok = $false; ms = $sw.ElapsedMilliseconds; raw = ('ERR ' + $_.Exception.Message + ' | BODY ' + $msg) }
    }
}

$log = "$T\cache_run.txt"
Set-Content -Path $log -Value "=== P7-B item2 POI cache cold/warm, start $(Get-Date -Format o) ===" -Encoding UTF8
$rows = "$T\cache_runs.tsv"
"idx,city,keyword,coldMs,coldMode,coldCount,warmMs,warmMode,warmCount" | Set-Content -Path $rows -Encoding UTF8

# ---- 0. login (explicit ContentType, must be first call) ----
$login = HttpCall 'POST' '/auth/login' '{"username":"admin","password":"Admin123456"}' $null
if (-not $login.ok) {
    Add-Content -Path $log -Value ("ABORT: login failed: " + $login.raw) -Encoding UTF8
    exit 2
}
$tok = ($login.raw | ConvertFrom-Json).data.token
if (-not $tok) {
    Add-Content -Path $log -Value "ABORT: token is null" -Encoding UTF8
    exit 2
}
Add-Content -Path $log -Value "login ok tokenLen=$($tok.Length)" -Encoding UTF8

# ---- 0b. verify an authenticated call works BEFORE touching the map ----
$probe = HttpCall 'GET' '/connector/map/status' $null $tok
if (-not $probe.ok) {
    Add-Content -Path $log -Value ("ABORT: authed probe failed: " + $probe.raw) -Encoding UTF8
    exit 2
}
Add-Content -Path $log -Value ("authed probe ok: " + $probe.raw.Substring(0, [Math]::Min(150, $probe.raw.Length))) -Encoding UTF8

# ---- 1. enable map ----
$en = HttpCall 'POST' '/admin/config/map/enabled' '{"enabled":true}' $tok
Add-Content -Path $log -Value ("map enabled -> ok=" + $en.ok + " " + $en.raw) -Encoding UTF8
Start-Sleep -Seconds 1

# ---- 2. clear POI cache keys ----
$keysResp = RedisCmd @('KEYS', 'map:poi*')
$keys = @()
foreach ($m in [regex]::Matches($keysResp, '\$(\d+)\r\n([^\r\n]+)')) {
    $k = $m.Groups[2].Value
    if ($k -notlike '*map:poi*') { continue }
    $keys += $k
}
foreach ($k in $keys) { RedisCmd @('DEL', $k) | Out-Null }
Add-Content -Path $log -Value "redis map:poi* matched=$($keys.Count) deleted=$($keys.Count)" -Encoding UTF8

# ---- 3. cold/warm pairs ----
$pairsJson = [IO.File]::ReadAllText("$env:TEMP\wf_p7b\poi_pairs.json", [Text.Encoding]::UTF8)
$pairs = $pairsJson | ConvertFrom-Json
$i = 0
$abort = $false
foreach ($p in $pairs) {
    $i++
    $q = '/connector/map/search?city=' + [uri]::EscapeDataString([string]$p.city) + '&keyword=' + [uri]::EscapeDataString([string]$p.keyword) + '&pageNum=1&pageSize=10'
    $cold = HttpCall 'GET' $q $null $tok
    $warm = HttpCall 'GET' $q $null $tok
    $cm = ''; $cc = ''; $wm = ''; $wc = ''
    try { $j = $cold.raw | ConvertFrom-Json; $cm = $j.data.mode; $cc = $j.data.count } catch {}
    try { $j2 = $warm.raw | ConvertFrom-Json; $wm = $j2.data.mode; $wc = $j2.data.count } catch {}
    "$i,$($p.city),$($p.keyword),$($cold.ms),$cm,$cc,$($warm.ms),$wm,$wc" | Add-Content -Path $rows -Encoding UTF8
    Add-Content -Path $log -Value ("[{0}] {1}/{2} cold={3}ms({4},n={5}) warm={6}ms({7},n={8})" -f $i, $p.city, $p.keyword, $cold.ms, $cm, $cc, $warm.ms, $wm, $wc) -Encoding UTF8
    if ($i -eq 1) {
        if ("$cc" -eq '0' -or "$cc" -eq '') {
            Add-Content -Path $log -Value ("ABORT: first cold call gave no data (AK/quota?) raw=" + $cold.raw.Substring(0, [Math]::Min(300, $cold.raw.Length))) -Encoding UTF8
            $abort = $true
            break
        }
    }
    Start-Sleep -Milliseconds 400
}

# ---- 4. restore map=off ----
$off = HttpCall 'POST' '/admin/config/map/enabled' '{"enabled":false}' $tok
Add-Content -Path $log -Value ("map enabled=false (restored) ok=" + $off.ok) -Encoding UTF8
$keysAfter = RedisCmd @('KEYS', 'map:poi*')
$nAfter = 0
foreach ($m in [regex]::Matches($keysAfter, '\$(\d+)\r\n([^\r\n]+)')) { if ($m.Groups[2].Value -like '*map:poi*') { $nAfter++ } }
Add-Content -Path $log -Value "redis map:poi* keys now=$nAfter  abort=$abort" -Encoding UTF8
Add-Content -Path $log -Value "=== all done $(Get-Date -Format o) ===" -Encoding UTF8
