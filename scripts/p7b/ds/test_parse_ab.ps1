$ErrorActionPreference = 'Continue'
$T = "$env:TEMP\wf_p7b\ds7"
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
    } catch {
        $sw.Stop(); return @{ ok = $false; ms = $sw.ElapsedMilliseconds; raw = ('ERR ' + $_.Exception.Message) }
    }
}

$log = "$T\ab.txt"
Set-Content -Path $log -Value "=== PARSE A/B: useProfile true vs false, $(Get-Date -Format o) ===" -Encoding UTF8

$login = HttpCall 'POST' '/auth/login' '{"username":"admin","password":"Admin123456"}' $null 20000
$tok = ($login.raw | ConvertFrom-Json).data.token

$inputs = @(
    '{"rawInput":"周末想去寿阳玩两天，喜欢古建筑，预算 500","useProfile":true}',
    '{"rawInput":"周末想去寿阳玩两天，喜欢古建筑，预算 500","useProfile":true}',
    '{"rawInput":"周末想去寿阳玩两天，喜欢古建筑，预算 500","useProfile":false}',
    '{"rawInput":"周末想去寿阳玩两天，喜欢古建筑，预算 500","useProfile":false}',
    '{"rawInput":"周末想去大同玩两天，喜欢古建筑，预算 500","useProfile":true}'
)
$i = 0
foreach ($b in $inputs) {
    $i++
    $r = HttpCall 'POST' '/trip/parse' $b $tok 120000
    $dest = ''; $comp = ''; $tags = ''
    try { $j = $r.raw | ConvertFrom-Json; $dest = $j.data.destination; $comp = $j.data.companion; $tags = ($j.data.preferenceTags -join ',') } catch {}
    $up = if ($b -like '*"useProfile":true*') { 'true' } else { 'false' }
    Add-Content -Path $log -Value ("[{0}] useProfile={1} ms={2} destination=[{3}] companion=[{4}] tags=[{5}]" -f $i, $up, $r.ms, $dest, $comp, $tags) -Encoding UTF8
    [IO.File]::WriteAllText("$T\ab_$i.json", $r.raw, (New-Object Text.UTF8Encoding($false)))
}
Add-Content -Path $log -Value "=== done $(Get-Date -Format o) ===" -Encoding UTF8
