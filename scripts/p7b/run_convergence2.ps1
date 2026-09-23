$ErrorActionPreference = 'Continue'
$T = "$env:TEMP\wf_p7b"
New-Item -ItemType Directory -Force -Path $T | Out-Null
$base = 'http://localhost:8080/api'

$w = New-Object Net.WebClient
$w.Encoding = [Text.Encoding]::UTF8
$w.Headers.Add('Content-Type', 'application/json')
$r = $w.UploadString("$base/auth/login", 'POST', '{"username":"admin","password":"Admin123456"}')
$tok = ($r | ConvertFrom-Json).data.token

$inputsJson = [IO.File]::ReadAllText("$T\inputs.json", [Text.Encoding]::UTF8)
$inputs = $inputsJson | ConvertFrom-Json

$log = "$T\convergence_run.txt"
Set-Content -Path $log -Value "=== P7-B item3 convergence: 30 real generations, start $(Get-Date -Format o) ===" -Encoding UTF8

$rows = "$T\convergence_runs.tsv"
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

$idx = 0
for ($round = 1; $round -le 5; $round++) {
    for ($k = 0; $k -lt $inputs.Count; $k++) {
        $idx++
        $payload = @{ rawInput = [string]$inputs[$k]; useProfile = $true }
        $body = $payload | ConvertTo-Json -Compress
        $bodyFile = "$T\body_$idx.json"
        [IO.File]::WriteAllText($bodyFile, $body, (New-Object Text.UTF8Encoding($false)))

        $t0 = Get-Date
        $res = Post '/trip/plan/sync' $body 300000
        $outFile = "$T\run_$idx.json"
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
        "$idx,$($k + 1),$tripId,$roundsN,$mapMode,$model,$durMs,$tokens,$estCost,$ce,$vCount,$highCount" | Add-Content -Path $rows -Encoding UTF8
        Add-Content -Path $log -Value ("[{0}] ok={1} httpSec={2} rounds={3} model={4} high={5} tokens={6} composeErr={7} t={8:HH:mm:ss}" -f $idx, $res.ok, $res.sec, $roundsN, $model, $highCount, $tokens, $ce, $t0) -Encoding UTF8
    }
}
Add-Content -Path $log -Value "=== all done $(Get-Date -Format o) ===" -Encoding UTF8