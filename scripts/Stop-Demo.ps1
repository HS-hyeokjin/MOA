. (Join-Path $PSScriptRoot 'Common.ps1')
$manifest = Join-Path $MoaRoot '.tools/private/demo.json'
if (-not (Test-Path -LiteralPath $manifest)) { Write-Output '실행 중인 데모 정보가 없습니다.'; return }
$demo = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
foreach ($kind in @('tunnel', 'server')) {
    $processId = if ($kind -eq 'tunnel') { [int]$demo.tunnelPid } else { [int]$demo.serverPid }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $processId"
    if (-not $process) { continue }
    $expected = if ($kind -eq 'tunnel') { Join-Path $MoaRoot '.tools/cloudflared.exe' } else { $demo.serverJar }
    $command = [string]$process.CommandLine
    if ($command.IndexOf($expected, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        Write-Warning "$kind PID가 다른 프로그램을 가리킵니다. 종료하지 않았습니다."
        continue
    }
    Stop-Process -Id $processId -ErrorAction SilentlyContinue
}
Remove-Item -LiteralPath $manifest
Write-Output 'MOA 데모 서버와 Tunnel을 중지했습니다. DB와 사진은 그대로 보관됩니다.'
