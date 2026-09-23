param([string]$Plan = "", [int]$Baseline = 573)
$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Net.Http | Out-Null
$T = "$env:TEMP\wf_p7b\e5"
New-Item -ItemType Directory -Force -Path $T | Out-Null
$base = 'http://localhost:8080/api'
$url = "$base/trip/plan/stream"

$rows = "$T\part2_valid.tsv"
if (-not (Test-Path $rows)) {
    "label,targetChars,ok,itineraryMs,deltaChars,deltaCount,totalMs,note" | Set-Content -Path $rows -Encoding UTF8
}
$logPath = "$T\part2_valid_run.txt"
if (-not (Test-Path $logPath)) {
    Set-Content -Path $logPath -Value "=== P7-B item5: abort at 20/50/80 pct, baseline=$Baseline chars ===" -Encoding UTF8
}

function ProbeHealth() {
    try {
        $c = New-Object Net.WebClient
        $c.Encoding = [Text.Encoding]::UTF8
        $r = $c.DownloadString("$base/health")
        return ($r -match '"status":"UP"')
    } catch { return $false }
}

if (-not (ProbeHealth)) {
    Add-Content -Path $logPath -Value "ABORTED: backend not healthy before start" -Encoding UTF8
    exit 2
}

$w = New-Object Net.WebClient
$w.Encoding = [Text.Encoding]::UTF8
$w.Headers.Add('Content-Type', 'application/json')
$r = $w.UploadString("$base/auth/login", 'POST', '{"username":"admin","password":"Admin123456"}')
$tok = ($r | ConvertFrom-Json).data.token

$inputsJson = [IO.File]::ReadAllText("$env:TEMP\wf_p7b\inputs.json", [Text.Encoding]::UTF8)
$inputs = $inputsJson | ConvertFrom-Json
$body = (@{ rawInput = [string]$inputs[0]; useProfile = $true } | ConvertTo-Json -Compress)

function StreamAbort([string]$label, [int]$targetChars) {
    $client = New-Object Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMinutes(12)
    $req = New-Object Net.Http.HttpRequestMessage('POST', $url)
    $req.Headers.Add('Authorization', "Bearer $tok")
    $req.Content = New-Object Net.Http.StringContent($body, [Text.Encoding]::UTF8, 'application/json')
    $cts = New-Object Threading.CancellationTokenSource

    $res = @{ ok = $false; itineraryMs = -1; deltaChars = 0; deltaCount = 0; totalMs = 0; note = '' }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $ev = ''
    try {
        $resp = $client.SendAsync($req, [Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cts.Token).Result
        if ($null -eq $resp) { $res.note = 'NULL_RESPONSE'; return $res }
        $stream = $resp.Content.ReadAsStreamAsync().Result
        $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8)
        while ($true) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            if ($line.StartsWith('event:')) { $ev = $line.Substring(6).Trim() }
            elseif ($line.StartsWith('data:')) {
                $data = $line.Substring(5).Trim()
                if ($ev -eq 'itinerary') {
                    if ($res.itineraryMs -lt 0) { $res.itineraryMs = $sw.ElapsedMilliseconds }
                } elseif ($ev -eq 'delta') {
                    $txt = ''
                    try { $txt = [string](($data | ConvertFrom-Json).text) } catch { $txt = '' }
                    $res.deltaChars += $txt.Length
                    $res.deltaCount++
                    if ($targetChars -gt 0 -and $res.deltaChars -ge $targetChars) {
                        $res.note = "CANCELLED_AT_$($res.deltaChars)_chars"
                        $cts.Cancel()
                        break
                    }
                } elseif ($ev -eq 'done') {
                    $res.ok = $true
                    $res.note = 'STREAM_COMPLETED_BEFORE_ABORT'
                    break
                } elseif ($ev -eq 'error') {
                    $res.note = 'ERROR_EVENT'
                    break
                }
            }
        }
    } catch {
        if ($res.note -eq '') { $res.note = 'EX: ' + $_.Exception.Message }
    } finally {
        $sw.Stop()
        $res.totalMs = $sw.ElapsedMilliseconds
        try { $client.Dispose() } catch {}
    }
    "$label,$targetChars,$($res.ok),$($res.itineraryMs),$($res.deltaChars),$($res.deltaCount),$($res.totalMs),$($res.note)" | Add-Content -Path $rows -Encoding UTF8
    Add-Content -Path $logPath -Value ("{0} target={1} ok={2} itineraryMs={3} deltaChars={4} deltaCount={5} totalMs={6} {7}" -f $label, $targetChars, $res.ok, $res.itineraryMs, $res.deltaChars, $res.deltaCount, $res.totalMs, $res.note) -Encoding UTF8
    Start-Sleep -Seconds 2
    return $res
}

foreach ($item in $Plan.Split(',')) {
    if ($item.Trim() -eq '') { continue }
    $parts = $item.Split(':')
    $pct = [int]$parts[0]
    $seq = [int]$parts[1]
    $target = [int]($Baseline * $pct / 100.0)
    if (-not (ProbeHealth)) {
        Add-Content -Path $logPath -Value "ABORTED_BACKEND_DOWN before $($item.Trim()) at $(Get-Date -Format o)" -Encoding UTF8
        exit 3
    }
    StreamAbort "ABORT_${pct}pct_$seq" $target | Out-Null
}
Add-Content -Path $logPath -Value "=== batch done $(Get-Date -Format o) ===" -Encoding UTF8
