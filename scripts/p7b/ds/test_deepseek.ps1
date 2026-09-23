$ErrorActionPreference = 'Continue'
$T = "$env:TEMP\wf_p7b\ds6"
New-Item -ItemType Directory -Force -Path $T | Out-Null
$base = 'http://localhost:8080/api'

function HttpCall($method, $path, $bodyText, $token, $timeoutMs) {
    if (-not $timeoutMs) { $timeoutMs = 60000 }
    $req = [Net.HttpWebRequest]::Create("$base$path")
    $req.Method = $method
    $req.Timeout = $timeoutMs
    $req.ReadWriteTimeout = $timeoutMs
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

$log = "$T\run.txt"
Set-Content -Path $log -Value "=== deepseek-flash test, start $(Get-Date -Format o) ===" -Encoding UTF8

$login = HttpCall 'POST' '/auth/login' '{"username":"admin","password":"Admin123456"}' $null 20000
if (-not $login.ok) { Add-Content $log "ABORT login: $($login.raw)"; exit 2 }
$tok = ($login.raw | ConvertFrom-Json).data.token
Add-Content -Path $log -Value "login ok tokenLen=$($tok.Length)" -Encoding UTF8

# ---- 1. 只 ping deepseek（不降级）----
$ping = HttpCall 'POST' '/diagnostics/llm/ping' '{"provider":"deepseek","prompt":"请只回复：OK"}' $tok 120000
[IO.File]::WriteAllText("$T\1_ping.json", $ping.raw, (New-Object Text.UTF8Encoding($false)))
Add-Content -Path $log -Value ("PING ok={0} ms={1} body={2}" -f $ping.ok, $ping.ms, $ping.raw.Substring(0, [Math]::Min(300, $ping.raw.Length))) -Encoding UTF8

# ---- 2. 切到 deepseek 并隔离（fallback 只留 deepseek，链尾只剩 mock）----
$sw = HttpCall 'POST' '/admin/config/llm' '{"activeProvider":"deepseek","fallbackOrder":"deepseek"}' $tok 20000
Add-Content -Path $log -Value ("SWITCH ok={0} body={1}" -f $sw.ok, $sw.raw) -Encoding UTF8

# ---- 3. PARSE（短输出）----
$p = HttpCall 'POST' '/trip/parse' '{"rawInput":"周末想去寿阳玩两天，喜欢古建筑，预算 500","useProfile":true}' $tok 300000
[IO.File]::WriteAllText("$T\2_parse.json", $p.raw, (New-Object Text.UTF8Encoding($false)))
Add-Content -Path $log -Value ("PARSE ok={0} ms={1} body={2}" -f $p.ok, $p.ms, $p.raw.Substring(0, [Math]::Min(400, $p.raw.Length))) -Encoding UTF8

# ---- 4. 完整管线（长输出：CANDIDATE + COMPOSE）----
$s = HttpCall 'POST' '/trip/plan/sync' '{"rawInput":"周末想去寿阳玩两天，喜欢古建筑，预算 500","useProfile":true}' $tok 900000
[IO.File]::WriteAllText("$T\3_sync.json", $s.raw, (New-Object Text.UTF8Encoding($false)))
Add-Content -Path $log -Value ("SYNC ok={0} ms={1} len={2}" -f $s.ok, $s.ms, $s.raw.Length) -Encoding UTF8
Add-Content -Path $log -Value ("SYNC head=" + $s.raw.Substring(0, [Math]::Min(500, $s.raw.Length))) -Encoding UTF8

Add-Content -Path $log -Value "=== done $(Get-Date -Format o) ===" -Encoding UTF8
