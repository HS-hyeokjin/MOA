param([string]$JavaHome, [int]$Port = 8080, [string]$DataDirectory)
. (Join-Path $PSScriptRoot 'Common.ps1')
if (-not $DataDirectory) { $DataDirectory = Join-Path $MoaRoot 'data' }
$dataPath = [System.IO.Path]::GetFullPath($DataDirectory)
$java = Get-MoaJava -JavaHome $JavaHome
$jar = Get-MoaServerJar
& $java '-jar' $jar "--server.port=$Port" "--moa.data-directory=$dataPath"
exit $LASTEXITCODE
