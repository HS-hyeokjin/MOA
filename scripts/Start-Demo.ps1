param([string]$JavaHome, [int]$Port = 8080)
. (Join-Path $PSScriptRoot 'Common.ps1')
$private = Initialize-MoaPrivateDirectory
$manifest = Join-Path $private 'demo.json'
if (Test-Path -LiteralPath $manifest) { throw '기존 데모 정보가 있습니다. scripts/Stop-Demo.ps1을 먼저 실행해 주세요.' }
$java = Get-MoaJava -JavaHome $JavaHome
$jar = Get-MoaServerJar
$cloudflared = Join-Path $MoaRoot '.tools/cloudflared.exe'
if (-not (Test-Path -LiteralPath $cloudflared)) { throw '.tools/cloudflared.exe가 필요합니다. docs/home-server.md를 참고하세요.' }
$data = Join-Path $MoaRoot 'data'
$server = $null
$tunnel = $null
try {
    $server = Start-Process -FilePath $java -ArgumentList @('-jar', "`"$jar`"", "--server.port=$Port", "`"--moa.data-directory=$data`"") -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $private 'demo-server.out.log') -RedirectStandardError (Join-Path $private 'demo-server.err.log')
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if ($server.HasExited) { throw '서버가 시작되지 않았습니다. .tools/private/demo-server.out.log를 확인해 주세요.' }
        try { if ((Invoke-RestMethod "http://127.0.0.1:$Port/api/v1/health" -TimeoutSec 1).status -eq 'UP') { $ready = $true; break } }
        catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw '서버 시작 시간 초과' }
    $tunnelLog = Join-Path $private 'demo-tunnel.err.log'
    $tunnel = Start-Process -FilePath $cloudflared -ArgumentList @('tunnel', '--no-autoupdate', '--url', "http://127.0.0.1:$Port", '--protocol', 'http2') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $private 'demo-tunnel.out.log') -RedirectStandardError $tunnelLog
    $address = $null
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($tunnel.HasExited) { throw 'Tunnel이 시작되지 않았습니다. .tools/private/demo-tunnel.err.log를 확인해 주세요.' }
        if (Test-Path -LiteralPath $tunnelLog) {
            $match = [regex]::Match((Get-Content -LiteralPath $tunnelLog -Raw), 'https://[a-z0-9-]+\.trycloudflare\.com')
            if ($match.Success) { $address = $match.Value; break }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $address) { throw '임시 HTTPS 주소 생성 시간 초과' }
    @{serverPid=$server.Id; tunnelPid=$tunnel.Id; serverJar=$jar; port=$Port; address=$address; startedAt=[datetimeoffset]::Now.ToString('o')} |
        ConvertTo-Json | Set-Content -LiteralPath $manifest -Encoding utf8
    Write-Output "임시 가족 서버 주소: $address"
    Write-Output '이 주소는 테스트용이며 PC나 Tunnel을 종료하면 사용할 수 없습니다. 중지는 scripts/Stop-Demo.ps1로 실행하세요.'
} catch {
    if ($tunnel -and -not $tunnel.HasExited) { Stop-Process -Id $tunnel.Id -ErrorAction SilentlyContinue }
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -ErrorAction SilentlyContinue }
    throw
}
