param([string]$JavaHome)
. (Join-Path $PSScriptRoot 'Common.ps1')
$java = Get-MoaJava -JavaHome $JavaHome
$keytool = Join-Path (Split-Path $java -Parent) 'keytool.exe'
$privateDirectory = Initialize-MoaPrivateDirectory
$keystore = Join-Path $privateDirectory 'moa-release.p12'
$properties = Join-Path $privateDirectory 'android-signing.properties'
if ((Test-Path -LiteralPath $keystore) -or (Test-Path -LiteralPath $properties)) {
    throw '기존 서명 파일을 덮어쓰지 않습니다. 기존 .tools/private 파일을 사용하거나 안전하게 백업해 주세요.'
}
$password = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
try {
    $env:MOA_KEYTOOL_PASSWORD = $password
    & $keytool '-genkeypair' '-keystore' $keystore '-storetype' 'PKCS12' '-alias' 'moa' '-keyalg' 'RSA' '-keysize' '3072' '-validity' '10000' '-dname' 'CN=MOA, OU=Family, O=MOA, C=KR' '-storepass:env' 'MOA_KEYTOOL_PASSWORD' '-keypass:env' 'MOA_KEYTOOL_PASSWORD' *> (Join-Path $privateDirectory 'keytool.log')
    if ($LASTEXITCODE -ne 0) { throw '서명 키 생성 실패. .tools/private/keytool.log를 확인해 주세요.' }
    $keystoreValue = $keystore.Replace('\', '/').Replace(':', '\:')
    [System.IO.File]::WriteAllText($properties, "storeFile=$keystoreValue`nstorePassword=$password`nkeyAlias=moa`nkeyPassword=$password`n")
    Write-Output "서명 설정 파일: $properties"
    Write-Output '업데이트 배포를 위해 키와 설정 파일을 별도로 안전하게 백업하세요. Git에는 포함되지 않습니다.'
} finally { Remove-Item Env:MOA_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue; $password = $null }
