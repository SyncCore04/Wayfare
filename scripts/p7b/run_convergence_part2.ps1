$ErrorActionPreference = 'Continue'
$T = "$env:TEMP\wf_p7b"
$base = 'http://localhost:8080/api'

$w = New-Object Net.WebClient
$w.Encoding = [Text.Encoding]::UTF8
$w.Headers.Add('Content-Type', 'application/json')
$r = $w.UploadString("$base/auth/login", 'POST', '{"username":"admin","password":"Admin123456"}')
$tok = ($r | ConvertFrom-Json).data.token

$inputsJson = [IO.File]::ReadAllText("$T\inputs.json", [Text.Encoding]::UTF8)
$inputs = $inputsJson | ConvertFrom-Json

$log = "$T\convergence_run_part2.txt"
Set-Content -Path $log -Value "=== P7-B item3 convergence PART2 (runs 23-30), start $(Get-Date -Format o) ===" -Encoding UTF8

$rows = "$T\convergence_runs_part2.tsv"
"idx,inputIdx,tripId,rounds,mapMode,model,durationMs,tokens,estCost,composeError,validationCount,highCount" | Set-Content -Path $rows -Encoding UTF8

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

# balanced top-up so every input reaches 5 samples:
# idx1..4 need 1 each, idx5/idx6 need 2 each = 8 runs, global idx 23..30
$plan = @(1, 2, 3, 4, 5, 6, 5, 6)
$idx = 22

foreach ($k in $plan) {
    $idx++
    $payload = @{ rawInput = [string]$inputs[$k - 1]; useProfile = $true }
    $body = $payload | ConvertTo-Json -Compress
    [IO.File]::WriteAllText("$T\p2_body_$idx.json", $body, (New-Object Text.UTF8Encoding($false)))

    $t0 = Get-Date
    $res = Post '/trip/plan/sync' $body 300000
    $outFile = "$T\p2_run_$idx.json"
    [IO.File]::WriteAllText($outFile, [string]$res.raw, (New-Object Text.UTF8Encoding($false)))

    $tripId = ''; $roundsN = ''; $mapMode = ''; $model = ''; $durMs = ''; $tokens = ''; $estCost = ''
    $ce = ''; $vCount = ''; $highCount = ''
    if ($res.ok) {
        $j = [IO.File]::ReadAllText($outFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
        $d = $j.data
        $tripId = $d.tripId
        $roundsN = $d.meta.rounds
        $mapMode = $d.meta.mapMode
        $model = $d.meta.modelName
        $durMs = $d.meta.durationMs
        $tokens = $d.meta.tokens
        $estCost = $d.meta.estCost
        $ce = [string]$d.composeError
        if ($d.validation) {
            $vCount = $d.validation.Count
            $h = @($d.validation | Where-Object { $_.high -eq $true })
            $highCount = $h.Count
        } else { $vCount = 0; $highCount = 0 }
    } else {
        $ce = 'HTTP_FAIL'
    }
    "$idx,$k,$tripId,$roundsN,$mapMode,$model,$durMs,$tokens,$estCost,$ce,$vCount,$highCount" | Add-Content -Path $rows -Encoding UTF8
    Add-Content -Path $log -Value ("[{0}] input={1} ok={2} httpSec={3} rounds={4} model={5} high={6} tokens={7} composeErr={8} t={9:HH:mm:ss}" -f $idx, $k, $res.ok, $res.sec, $roundsN, $model, $highCount, $tokens, $ce, $t0) -Encoding UTF8
}
Add-Content -Path $log -Value "=== all done $(Get-Date -Format o) ===" -Encoding UTF8
