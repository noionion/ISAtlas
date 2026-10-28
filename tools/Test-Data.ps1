param([string]$DataDirectory, [string]$GradleExecutable, [string]$GradleUserHome, [switch]$Offline)
. (Join-Path $PSScriptRoot 'Data-Common.ps1')
$repository = Get-DataRepository $DataDirectory
$assets = Get-DataAssets $repository
Push-Location (Join-Path $PSScriptRoot '..')
try {
    Invoke-DataGradle $GradleExecutable $GradleUserHome $Offline.IsPresent @(
        "-Pisatlas.dataDir=$assets", ':core:validateOfficialPackages', ':core:test', ':app:lintDebug', ':app:assembleDebug')
    Write-Output 'Candidate data validated and debug APK built; tracked baseline assets were not changed.'
} finally { Pop-Location }
