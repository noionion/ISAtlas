param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$SigningDirectory,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$signingDirectoryPath = (Resolve-Path -LiteralPath $SigningDirectory).Path
$credentials = @{}
Get-Content -LiteralPath (Join-Path $signingDirectoryPath 'android-release-password.txt') -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*(.+?)\s*[:=：]\s*(.+?)\s*$') {
        $credentials[$matches[1]] = $matches[2]
    }
}
# Read only the required fields; never echo credential values or pass them as CLI arguments.
$aliasEntry = @($credentials.Keys | Where-Object { $_ -match 'Key alias' })
$storeEntry = @($credentials.Keys | Where-Object { $_ -match 'Keystore password' })
$keyEntry = @($credentials.Keys | Where-Object { $_ -match 'Key password' })
if ($aliasEntry.Count -ne 1 -or $storeEntry.Count -ne 1 -or $keyEntry.Count -ne 1) {
    throw 'Signing credentials must define Key alias, Keystore password, and Key password.'
}
$signingEnvironment = @{
    ISATLAS_KEYSTORE = Join-Path $signingDirectoryPath 'android-release.jks'
    ISATLAS_KEY_ALIAS = $credentials[$aliasEntry[0]]
    ISATLAS_STORE_PASSWORD = $credentials[$storeEntry[0]]
    ISATLAS_KEY_PASSWORD = $credentials[$keyEntry[0]]
}
$previousEnvironment = @{}
Push-Location $projectRoot
try {
    foreach ($name in $signingEnvironment.Keys) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $signingEnvironment[$name], 'Process')
    }
    $gradle = Join-Path $projectRoot '.tools/gradle-8.11.1/bin/gradle.bat'
    $gradleArgs = @('--no-daemon', '--console=plain')
    if (Test-Path -LiteralPath $gradle) {
        $gradleArgs += @('-g', (Join-Path $projectRoot '.tools/gradle-user-home'))
    } else {
        $gradle = Join-Path $projectRoot 'gradlew.bat'
    }
    if ($Offline) { $gradleArgs += '--offline' }
    & $gradle @gradleArgs ':core:test' ':app:lintRelease' ':app:assembleRelease'
    if ($LASTEXITCODE -ne 0) { throw 'Release build or validation failed.' }

    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    $buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $buildTools) { throw 'Android SDK build-tools not found.' }
    $apk = Join-Path $projectRoot 'app/build/outputs/apk/release/app-release.apk'
    $verification = & (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $pem = Get-Content -LiteralPath (Join-Path $signingDirectoryPath 'android-release-public.pem') -Raw
    $certificateBytes = [Convert]::FromBase64String(($pem -replace '-----[^-]+-----', '' -replace '\s', ''))
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try { $expectedCertificate = ([BitConverter]::ToString($sha256.ComputeHash($certificateBytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha256.Dispose() }
    $actualCertificate = ($verification | Select-String '^Signer #1 certificate SHA-256 digest: (.+)$').Matches.Groups[1].Value.Trim().ToLowerInvariant()
    if ($actualCertificate -ne $expectedCertificate) { throw 'APK signer does not match the supplied public certificate.' }

    $config = Get-Content -LiteralPath 'app/build.gradle.kts' -Raw
    $versionName = [regex]::Match($config, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    $versionCode = [regex]::Match($config, 'versionCode\s*=\s*(\d+)').Groups[1].Value
    $badging = & (Join-Path $buildTools.FullName 'aapt.exe') dump badging $apk
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read APK metadata.' }
    $package = $badging | Select-String '^package:'
    if ($package -notmatch "name='dev.isatlas'" -or $package -notmatch "versionCode='$versionCode'" -or
        $package -notmatch "versionName='$([regex]::Escape($versionName))'") { throw 'APK version does not match the build configuration.' }
    if ($badging -match '^application-debuggable') { throw 'Release APK must not be debuggable.' }
    $output = Join-Path $projectRoot ".tools/releases/$versionName"
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $fileName = "ISAtlas-$versionName.apk"
    $destination = Join-Path $output $fileName
    Copy-Item -LiteralPath $apk -Destination $destination
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $output "$fileName.sha256"), "$hash  $fileName`n", [Text.UTF8Encoding]::new($false))
    Write-Output "Verified release: $destination"
    Write-Output "Version: $versionName ($versionCode)"
    Write-Output "Certificate SHA-256: $actualCertificate"
    Write-Output "APK SHA-256: $hash"
} finally {
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    $credentials.Clear()
    $signingEnvironment.Clear()
    Pop-Location
}
