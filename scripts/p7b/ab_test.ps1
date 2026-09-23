$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Net.Http | Out-Null
$T = "$env:TEMP\wf_p7b\ab"
New-Item -ItemType Directory -Force -Path $T | Out-Null
$base = 'http://localhost:8080/api'
$url = "$base/trip/plan/stream"
$log = "$T\ab.txt"
Set-Content -Path $log -Value "=== A/B test: full stream vs abort, start $(Get-Date -Format o) ===" -Encoding UTF8

function Probe([string]$tag) {
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        try {
            $c = New-Object Net.WebClient
            $c.Encoding = [Text.Encoding]::UTF8
            $r = $c.DownloadString("$base/health")
            if ($r -match '"status":"UP"') {
                Add-Content -Path $log -Value "$tag : HEALTHY at $(Get-Date -Format o)" -Encoding UTF8
                return $true
            }
        } catch { }
        Start-Sleep -Seconds 1
    }
    Add-Content -Path $log -Value "$tag : DEAD at $(Get-Date -Format o)" -Encoding UTF8
    return $false
}

$w = New-Object Net.WebClient
$w.Encoding = [Text.Encoding]::UTF8
$w.Headers.Add('Content-Type', 'application/json')
$r = $w.UploadString("$base/auth/login", 'POST', '{"username":"admin","password":"Admin123456"}')
$tok = ($r | ConvertFrom-Json).data.token
$inputsJson = [IO.File]::ReadAllText("$env:TEMP\wf_p7b\inputs.json", [Text.Encoding]::UTF8)
$inputs = $inputsJson | ConvertFrom-Json
$body = (@{ rawInput = [string]$inputs[0]; useProfile = $true } | ConvertTo-Json -Compress)

function Run([string]$label, [int]$targetChars) {
    $client = New-Object Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMinutes(12)
    $req = New-Object Net.Http.HttpRequestMessage('POST', $url)
    $req.Headers.Add('Authorization', "Bearer $tok")
    $req.Content = New-Object Net.Http.StringContent($body, [Text.Encoding]::UTF8, 'application/json')
    $cts = New-Object Threading.CancellationTokenSource
    $itMs = -1; $dc = 0; $note = ''
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
                if ($ev -eq 'itinerary') { if ($itMs -lt 0) { $itMs = $sw.ElapsedMilliseconds } }
                elseif ($ev -eq 'delta') {
                    $txt = ''
                    try { $txt = [string](($data | ConvertFrom-Json).text) } catch { $txt = '' }
                    $dc += $txt.Length
                    if ($targetChars -gt 0 -and $dc -ge $targetChars) { $note = "CANCELLED_AT_${dc}_chars"; $cts.Cancel(); break }
                } elseif ($ev -eq 'done') { $note = 'DONE'; break }
                elseif ($ev -eq 'error') { $note = 'ERROR_EVENT'; break }
            }
        }
    } catch { if ($note -eq '') { $note = 'EX: ' + $_.Exception.Message } } finally {
        $sw.Stop()
        try { $client.Dispose() } catch {}
    }
    Add-Content -Path $log -Value ("{0}: itineraryMs={1} deltaChars={2} totalMs={3} {4}" -f $label, $itMs, $dc, $sw.ElapsedMilliseconds, $note) -Encoding UTF8
    return $note
}

if (-not (Probe "BEFORE")) { exit 2 }
Run "TEST_A_FULL_STREAM" 0 | Out-Null
Probe "AFTER_A_FULL_STREAM" | Out-Null
Run "TEST_B_ABORT" 115 | Out-Null
Probe "AFTER_B_ABORT" | Out-Null
Add-Content -Path $log -Value "=== done $(Get-Date -Format o) ===" -Encoding UTF8
