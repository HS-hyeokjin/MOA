$ErrorActionPreference = 'Stop'
$MoaRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Get-MoaJava {
    param([string]$JavaHome)
    $candidates = @($JavaHome, $env:JAVA_HOME, 'D:\java\jdk-21') | Where-Object { $_ }
    foreach ($candidate in $candidates) {
        $executable = Join-Path $candidate 'bin/java.exe'
        if (-not (Test-Path -LiteralPath $executable)) { continue }
        $details = (& $executable '-XshowSettings:properties' '-version' 2>&1 | Out-String)
        if ($details -match 'java.specification.version\s*=\s*21\b') { return $executable }
    }
    throw 'JDK 21이 필요합니다. -JavaHome으로 JDK 21 설치 경로를 지정해 주세요.'
}

function Get-MoaServerJar {
    $jar = Get-ChildItem -LiteralPath (Join-Path $MoaRoot 'server/build/libs') -Filter '*.jar' |
        Where-Object { $_.Name -notlike '*-plain.jar' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw 'server 폴더에서 gradlew.bat bootJar를 먼저 실행해 주세요.' }
    return $jar.FullName
}

function Initialize-MoaPrivateDirectory {
    $directory = Join-Path $MoaRoot '.tools/private'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    if ($env:OS -eq 'Windows_NT') {
        $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl = Get-Acl -LiteralPath $directory
        $allowed = @($identity.Value, 'S-1-5-18', 'S-1-5-32-544')
        $rules = @($acl.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier]))
        $unexpected = @($rules | Where-Object { $_.AccessControlType -eq 'Allow' -and $_.IdentityReference.Value -notin $allowed })
        $ownerAccess = @($rules | Where-Object { $_.IdentityReference.Value -eq $identity.Value -and $_.AccessControlType -eq 'Allow' -and ($_.FileSystemRights -band [System.Security.AccessControl.FileSystemRights]::FullControl) -eq [System.Security.AccessControl.FileSystemRights]::FullControl })
        if ($acl.AreAccessRulesProtected -and $unexpected.Count -eq 0 -and $ownerAccess.Count -gt 0) { return $directory }
        $acl.SetAccessRuleProtection($true, $false)
        foreach ($rule in $rules) { $acl.RemoveAccessRuleSpecific($rule) }
        foreach ($principal in @($identity, [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'), [System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
            $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($principal, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
            $acl.AddAccessRule($rule)
        }
        Set-Acl -LiteralPath $directory -AclObject $acl
    }
    return $directory
}
