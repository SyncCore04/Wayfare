$ErrorActionPreference = 'Continue'
$T = "$env:TEMP\wf_p7b"
New-Item -ItemType Directory -Force -Path $T | Out-Null
$base = 'http://localhost:8080/api'

# ---- 登录 ----
$w = New-Object Net.WebClient
$w.Encoding = [Text.Encoding]::UTF8
$w.Headers.Add('Content-Type', 'application/json')
$r = $w.UploadString("$base/auth/login", 'POST', '{"username":"admin","password":"Admin123456"}')
$tok = ($r | ConvertFrom-Json).data.token

$inputs = @(
    '周末想去寿阳玩两天，喜欢古建筑，预算 500',
    '三天，想去大同看古建和博物馆，不吃辣，一个人，预算 1500',
    '两天，带爸妈去寿阳，走不动，想轻松点，想吃面食',
    '想去寿阳玩一天，喜欢寺庙，预算 300',
    '两天，想去寿阳看古建筑和博物馆，预算 800，节奏紧凑一点',
    '三天，想去大同看古建筑，喜欢摄影，预算 2000'
)

$log = "$T\convergence_run.txt"
Set-Content -Path $log -Value "=== P7-B 第三项：校验收敛率（30 次真实生成）start $(Get-Date -Format o) ===" -Encoding UTF8

$rows = "$T\convergence_runs.tsv"
"idx,inputIdx,tripId,rounds,mapMode,model,durationMs,tokens,composeError,validationCount,highCount" | Set-Content -Path $rows -Encoding UTF8

function Post($path, $bodyText, $timeoutMs) {
    $u = "$base$path"
    $req = [Net.HttpWebRequest]::Create($u)
    $req.Method = 'POST'
    $req.Timeout = $timeoutMs
    $req.ReadWriteTimeout = $timeoutMs
    $req.ContentType = 'application/json'
    $req.Headers.Add('Authorization', "Bearer $tok")
    $bytes = [Text.Encoding]::UTF8.GetBytes($bodyText)
    $req.ContentLength = $bytes.Length
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $s = $req.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
        $resp = $req.GetResponse()
        $sr = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
        $out = $sr.ReadToEnd(); $sr.Close(); $resp.Close(); $sw.Stop()
        return @{ ok = $true; sec = [math]::Round($sw.Elapsed.TotalSeconds, 1); raw = $out }
    } catch {
        $sw.Stop()
        $msg = ''
        try { $sr = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream(), [Text.Encoding]::UTF8); $msg = $sr.ReadToEnd() } catch {}
        return @{ ok = $false; sec = [math]::Round($sw.Elapsed.TotalSeconds, 1); raw = ("ERR " + $_.Exception.Message + " | BODY " + $msg) }
    }
}

$idx = 0
for ($round = 1; $round -le 5; $round++) {
    for ($k = 0; $k -lt $inputs.Count; $k++) {
        $idx++
        $body = (@{ rawInput = $inputs[$k]; useProfile = $true } | ConvertTo-Json -Compress)
        $bodyFile = "$T\body_$idx.json"
        [IO.File]::WriteAllText($bodyFile, $body, (New-Object Text.UTF8Encoding($false)))

        $t0 = Get-Date
        $res = Post '/trip/plan/sync' $body 300000
        $outFile = "$T\run_$idx.json"
        [IO.File]::WriteAllText($outFile, [string]$res.raw, (New-Object Text.UTF8Encoding($false)))

        $tripId = ''; $roundsN = ''; $mapMode = ''; $model = ''; $durMs = ''; $tokens = ''; $ce = ''
        $vCount = ''; $highCount = ''
        if ($res.ok) {
            $j = [IO.File]::ReadAllText($outFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
            $d = $j.data
            $tripId = $d.tripId
            $roundsN = $d.meta.rounds
            $mapMode = $d.meta.mapMode
            $model = $d.meta.modelName
            $durMs = $d.meta.durationMs
            $tokens = $d.meta.tokens
            $ce = [string]$d.composeError
            if ($d.validation) {
                $vCount = $d.validation.Count
                $highCount = ($d.validation | Where-Object { $_.high -eq $true }).Count
                if ($null -eq $highCount) { $highCount = 0 }
            } else { $vCount = 0; $highCount = 0 }
        } else {
            $ce = 'HTTP_FAIL'
        }
        "$idx,$($k + 1),$tripId,$roundsN,$mapMode,$model,$durMs,$tokens,$ce,$vCount,$highCount" | Add-Content -Path $rows -Encoding UTF8
        Add-Content -Path $log -Value ("[$idx] ok={0} httpSec={1} rounds={2} model={3} high={4} composeErr={5} started={6:HH:mm:ss}" -f $res.ok, $res.sec, $roundsN, $model, $highCount, $ce, $t0) -Encoding UTF8
    }
}
Add-Content -Path $log -Value "=== all done $(Get-Date -Format o) ===" -Encoding UTF8