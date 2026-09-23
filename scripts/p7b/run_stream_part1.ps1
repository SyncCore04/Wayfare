$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Net.Http | Out-Null
$T = "$env:TEMP\wf_p7b\e4"
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

$logPath = "$T\part1_run.txt"
Set-Content -Path $logPath -Value "=== P7-B item4: streaming timing, 5 full streams, start $(Get-Date -Format o) ===" -Encoding UTF8
$rows = "$T\part1.tsv"
"idx,ok,itineraryMs,doneMs,deltaChars,deltaCount,totalMs,note" | Set-Content -Path $rows -Encoding UTF8

function StreamOnce([int]$i) {
    $client = New-Object Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMinutes(12)
    $req = New-Object Net.Http.HttpRequestMessage('POST', $url)
    $req.Headers.Add('Authorization', "Bearer $tok")
    $req.Content = New-Object Net.Http.StringContent($body, [Text.Encoding]::UTF8, 'application/json')

    $res = @{ ok = $false; itineraryMs = -1; doneMs = -1; deltaChars = 0; deltaCount = 0; totalMs = 0; note = '' }
    $stages = New-Object System.Collections.ArrayList
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $ev = ''
    try {
        $resp = $client.SendAsync($req, [Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
        $stream = $resp.Content.ReadAsStreamAsync().Result
        $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8)
        while ($true) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            if ($line.StartsWith('event:')) { $ev = $line.Substring(6).Trim() }
            elseif ($line.StartsWith('data:')) {
                $data = $line.Substring(5).Trim()
                if ($ev -eq 'stage') {
                    $nm = ''; $st = ''
                    try { $j = $data | ConvertFrom-Json; $nm = [string]$j.stage; $st = [string]$j.status } catch {}
                    [void]$stages.Add(("{0},{1},{2}" -f $nm, $st, $sw.ElapsedMilliseconds))
                } elseif ($ev -eq 'itinerary') {
                    if ($res.itineraryMs -lt 0) { $res.itineraryMs = $sw.ElapsedMilliseconds }
                } elseif ($ev -eq 'delta') {
                    $txt = ''
                    try { $txt = [string](($data | ConvertFrom-Json).text) } catch { $txt = '' }
                    $res.deltaChars += $txt.Length
                    $res.deltaCount++
                } elseif ($ev -eq 'done') {
                    $res.doneMs = $sw.ElapsedMilliseconds
                    $res.ok = $true
                    break
                } elseif ($ev -eq 'error') {
                    $res.note = 'ERROR_EVENT: ' + $data.Substring(0, [Math]::Min(200, $data.Length))
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
    $stages | Set-Content -Path "$T\part1_stages_$i.txt" -Encoding UTF8
    "$i,$($res.ok),$($res.itineraryMs),$($res.doneMs),$($res.deltaChars),$($res.deltaCount),$($res.totalMs),$($res.note)" | Add-Content -Path $rows -Encoding UTF8
    Add-Content -Path $logPath -Value ("[{0}] ok={1} itineraryMs={2} doneMs={3} deltaChars={4} deltaCount={5} totalMs={6} {7}" -f $i, $res.ok, $res.itineraryMs, $res.doneMs, $res.deltaChars, $res.deltaCount, $res.totalMs, $res.note) -Encoding UTF8
    return $res
}

$all = @()
for ($i = 1; $i -le 5; $i++) { $all += StreamOnce $i }
$ok = @($all | Where-Object { $_.ok -eq $true })
Add-Content -Path $logPath -Value ("SUMMARY ok={0}/5 avgItineraryMs={1} avgDoneMs={2} avgDeltaChars={3}" -f $ok.Count, [int](($ok | Measure-Object -Property itineraryMs -Average).Average), [int](($ok | Measure-Object -Property doneMs -Average).Average), [int](($ok | Measure-Object -Property deltaChars -Average).Average)) -Encoding UTF8
Add-Content -Path $logPath -Value "=== all done $(Get-Date -Format o) ===" -Encoding UTF8
