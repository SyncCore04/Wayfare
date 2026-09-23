$ErrorActionPreference = 'Continue'
$out = "$env:TEMP\wf_p7b\preflight.txt"

function RedisCmd([string[]]$parts) {
    $c = New-Object Net.Sockets.TcpClient('127.0.0.1', 6379)
    $s = $c.GetStream()
    $sb = New-Object Text.StringBuilder
    [void]$sb.Append('*' + $parts.Count + "`r`n")
    foreach ($p in $parts) { [void]$sb.Append('$' + $p.Length + "`r`n" + $p + "`r`n") }
    $bytes = [Text.Encoding]::UTF8.GetBytes($sb.ToString())
    $s.Write($bytes, 0, $bytes.Length); $s.Flush()
    Start-Sleep -Milliseconds 300
    $buf = New-Object byte[] 65536
    $n = $s.Read($buf, 0, $buf.Length)
    $c.Close()
    return [Text.Encoding]::UTF8.GetString($buf, 0, $n)
}

Set-Content -Path $out -Value "=== breaker keys BEFORE ===" -Encoding UTF8
$keysResp = RedisCmd @('KEYS', 'cb:*')
Add-Content -Path $out -Value $keysResp -Encoding UTF8

$keys = @()
foreach ($m in [regex]::Matches($keysResp, '\$(\d+)\r\n([^\r\n]+)')) {
    $keys += $m.Groups[2].Value
}
Add-Content -Path $out -Value "matched=$($keys.Count) : $($keys -join ', ')" -Encoding UTF8

foreach ($k in $keys) {
    $r = RedisCmd @('DEL', $k)
    Add-Content -Path $out -Value "DEL $k -> $r" -Encoding UTF8
}

Add-Content -Path $out -Value ("=== after DEL $(Get-Date -Format o) ===") -Encoding UTF8
Add-Content -Path $out -Value "--- AFTER ---" -Encoding UTF8
Add-Content -Path $out -Value (RedisCmd @('KEYS', 'cb:*')) -Encoding UTF8
Add-Content -Path $out -Value "=== done ===" -Encoding UTF8
