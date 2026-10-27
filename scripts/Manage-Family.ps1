param(
    [Parameter(Mandatory = $true)][ValidateSet('me', 'sister', 'brother-in-law', 'mom', 'dad')][string]$Member,
    [switch]$Revoke,
    [string]$JavaHome,
    [string]$DataDirectory,
    [string]$DisplayName
)
. (Join-Path $PSScriptRoot 'Common.ps1')
if (-not $DataDirectory) { $DataDirectory = Join-Path $MoaRoot 'data' }
$java = Get-MoaJava -JavaHome $JavaHome
$jar = Get-MoaServerJar
$dataPath = [System.IO.Path]::GetFullPath($DataDirectory)
$privateDirectory = Initialize-MoaPrivateDirectory
$arguments = @('-jar', $jar, '--spring.main.web-application-type=none', "--moa.data-directory=$dataPath", "--moa.admin.member=$Member")
if ($DisplayName) { $arguments += "--moa.admin.display-name=$DisplayName" }
if ($Revoke) {
    $arguments += '--moa.admin.command=revoke'
    & $java @arguments *> (Join-Path $privateDirectory 'family-admin.log')
    if ($LASTEXITCODE -ne 0) { throw '로그인 해제 실패. .tools/private/family-admin.log를 확인해 주세요.' }
    Write-Output "$Member 계정의 모든 기기 로그인과 남은 초대코드를 해제했습니다."
} else {
    $output = Join-Path $privateDirectory ("invite-$Member-" + [guid]::NewGuid().ToString('N') + '.txt')
    $arguments += @('--moa.admin.command=invite', "--moa.admin.output=$output")
    & $java @arguments *> (Join-Path $privateDirectory 'family-admin.log')
    if ($LASTEXITCODE -ne 0) { throw '초대코드 생성 실패. .tools/private/family-admin.log를 확인해 주세요.' }
    Write-Output "초대코드 파일: $output"
    Write-Output '코드는 파일에서 확인하세요. 7일 이내 한 번 사용할 수 있고, 재발급하면 이전 미사용 코드는 만료됩니다.'
}
