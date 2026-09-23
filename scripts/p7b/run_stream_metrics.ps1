$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Net.Http | Out-Null
$T = "$env:TEMP\wf_p7b\d"
New-Item -ItemType Directory -Force -Path $T | Out-Null
$base = 'http://localhost:8080/api'
$url = "$base/trip/plan/stream"

$w = New-Object Net.WebClient
$w.Encoding = [Text.Encoding]::UTF8
$w.Headers.Add('Content-Type', 'application/json')
$r = $w.UploadString("$base/auth/login", 'POST', '{"username":"admin","password":"Admin123456"}')
$tok = ($r | ConvertFrom-Json).data.token

$inputsJson = [IO.File]::ReadAllText("$env:TEMP\wf_p7b\inputs.json", [Text.Encoding]::UTF8)
$inputs = $inputsJson | ConvertFrom-Json
$body = (@{ rawInput = [string]$inputs[0]; useProfile = $true } | ConvertTo-Json -Compress)

$logPath = "$T\d_run.txt"
Set-Content -Path $logPath -Value "=== P7-B item4/5: streaming timing + interruption, start $(Get-Date -Format o) ===" -Encoding UTF8

function StreamOnce([int]$cancelAfterDeltaChars, [string]$label) {
    $client = New-Object Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMinutes(12)
    $req = New-Object Net.Http.HttpRequestMessage('POST', $url)
    $req.Headers.Add('Authorization', "Bearer $tok")
    $req.Content = New-Object Net.Http.StringContent($body, [Text.Encoding]::UTF8, 'application/json')
    $cts = New-Object Threading.CancellationTokenSource

    $res = @{ ok = $false; itineraryMs = -1; doneMs = -1; deltaChars = 0; deltaCount = 0; totalMs = 0; note = '' }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $ev = ''
    try {
        $resp = $client.SendAsync($req, [Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cts.Token).Result
        $stream = $resp.Content.ReadAsStreamAsync().Result
        $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8)
        while ($true) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            if ($line.StartsWith('event:')) { $ev = $line.Substring(6).Trim() }
            elseif ($line.StartsWith('data:')) {
                $data = $line.Substring(5).Trim()
                if ($ev -eq 'delta') {
                    $txt = ''
                    try { $txt = [string](($data | ConvertFrom-Json).text) } catch { $txt = '' }
                    $res.deltaChars += $txt.Length
                    $res.deltaCount++
                    if ($cancelAfterDeltaChars -gt 0 -and $res.deltaChars -ge $cancelAfterDeltaChars) {
                        $res.note = "CANCELLED_AT_$($res.deltaChars)_chars"
                        $cts.Cancel()
                        break
                    }
                } elseif ($ev -eq 'itinerary') {
                    $res.itineraryMs = $sw.ElapsedMilliseconds
                } elseif ($ev -eq 'done') {
                    $res.doneMs = $sw.ElapsedMilliseconds
                    $res.ok = $true
                    break
                }
            }
        }
    } catch {
        $res.note = 'EX: ' + $_.Exception.Message
    } finally {
        $sw.Stop()
        $res.totalMs = $sw.ElapsedMilliseconds
        try { $client.Dispose() } catch {}
    }
    Add-Content -Path $logPath -Value ("{0}: ok={1} itineraryMs={2} doneMs={3} deltaChars={4} deltaCount={5} totalMs={6} {7}" -f $label, $res.ok, $res.itineraryMs, $res.doneMs, $res.deltaChars, $res.deltaCount, $res.totalMs, $res.note) -Encoding UTF8
    return $res
}

# ---- Part 1: 5 full streams (timing) ----
$full = @()
for ($i = 1; $i -le 5; $i++) {
    $full += StreamOnce 0 "FULL_$i"
}
$charsList = $full | Where-Object { $_.deltaChars -gt 0 } | ForEach-Object { $_.deltaChars }
$baseline = 0
if ($charsList.Count -gt 0) {
    $baseline = [int](($charsList | Measure-Object -Average).Average)
}
"PART1_SUMMARY baselineAvgDeltaChars=$baseline samples=$($charsList.Count)" | Add-Content -Path $logPath -Encoding UTF8
$full | ForEach-Object { $_.itineraryMs } | Set-Content -Path "$T\part1_itinerary_ms.txt" -Encoding UTF8
$full | ForEach-Object { $_.doneMs } | Set-Content -Path "$T\part1_done_ms.txt" -Encoding UTF8

# ---- Part 2: 9 interruptions at 20% / 50% / 80% ----
foreach ($pct in @(20, 50, 80)) {
    $target = [int]($baseline * $pct / 100.0)
    for ($j = 1; $j -le 3; $j++) {
        StreamOnce $target "ABORT_${pct}pct_$j" | Out-Null
    }
}

Add-Content -Path $logPath -Value "=== all done $(Get-Date -Format o) ===" -Encoding UTF8