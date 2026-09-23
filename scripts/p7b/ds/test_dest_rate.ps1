$ErrorActionPreference = 'Continue'
$T = "$env:TEMP\wf_p7b\ds8"
New-Item -ItemType Directory -Force -Path $T | Out-Null
$base = 'http://localhost:8080/api'

function HttpCall($method, $path, $bodyText, $token, $timeoutMs) {
    if (-not $timeoutMs) { $timeoutMs = 120000 }
    $req = [Net.HttpWebRequest]::Create("$base$path")
    $req.Method = $method; $req.Timeout = $timeoutMs; $req.ReadWriteTimeout = $timeoutMs
    if ($token) { $req.Headers.Add('Authorization', "Bearer $token") }
    if ($bodyText) {
        $req.ContentType = 'application/json'
        $b = [Text.Encoding]::UTF8.GetBytes($bodyText); $req.ContentLength = $b.Length
        $s = $req.GetRequestStream(); $s.Write($b, 0, $b.Length); $s.Close()
    }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $resp = $req.GetResponse()
        $sr = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
        $out = $sr.ReadToEnd(); $sr.Close(); $resp.Close(); $sw.Stop()
        return @{ ok = $true; ms = $sw.ElapsedMilliseconds; raw = $out }
    } catch { $sw.Stop(); return @{ ok = $false; ms = $sw.ElapsedMilliseconds; raw = 'ERR' } }
}

$log = "$T\rate.txt"
Set-Content -Path $log -Value "=== 寿阳 destination 误判率, $(Get-Date -Format o) ===" -Encoding UTF8
$login = HttpCall 'POST' '/auth/login' '{"username":"admin","password":"Admin123456"}' $null 20000
$tok = ($login.raw | ConvertFrom-Json).data.token

$body = '{"rawInput":"周末想去寿阳玩两天，喜欢古建筑，预算 500","useProfile":true}'
for ($i = 1; $i -le 8; $i++) {
    $r = HttpCall 'POST' '/trip/parse' $body $tok 120000
    $d = ''
    try { $d = ($r.raw | ConvertFrom-Json).data.destination } catch {}
    Add-Content -Path $log -Value ("[{0}] ms={1} destination=[{2}]" -f $i, $r.ms, $d) -Encoding UTF8
}
Add-Content -Path $log -Value "=== done ===" -Encoding UTF8
