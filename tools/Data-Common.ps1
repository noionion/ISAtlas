$ErrorActionPreference = 'Stop'

function Get-DataRepository([string]$DataDirectory) {
    if (-not $DataDirectory) {
        $configuration = Join-Path $PSScriptRoot '../data.local.properties'
        if (Test-Path -LiteralPath $configuration) {
            foreach ($line in Get-Content -LiteralPath $configuration -Encoding UTF8) {
                if ($line -match '^\s*data\.dir\s*=\s*(.+?)\s*$') { $DataDirectory = $matches[1] }
            }
        }
    }
    if (-not $DataDirectory) { throw 'Specify -DataDirectory or set data.dir in ignored data.local.properties.' }
    $directory = (Resolve-Path -LiteralPath $DataDirectory).Path
    if (-not (Test-Path -LiteralPath (Join-Path $directory 'MIGRATION.json'))) {
        throw 'Expected an ISAtlas-data repository directory.'
    }
    return $directory
}

function Invoke-DataGradle([string]$GradleExecutable, [string]$GradleUserHome, [bool]$Offline, [string[]]$Tasks) {
    if (-not $GradleExecutable) {
        $GradleExecutable = Join-Path $PSScriptRoot $(if ($IsWindows -or $env:OS -eq 'Windows_NT') { '../gradlew.bat' } else { '../gradlew' })
    }
    $arguments = @('--console=plain')
    if ($GradleUserHome) { $arguments += @('-g', $GradleUserHome) }
    if ($Offline) { $arguments += '--offline' }
    & $GradleExecutable @arguments @Tasks
    if ($LASTEXITCODE -ne 0) { throw 'Data compatibility/build validation failed.' }
}

function Get-DataAssets([string]$Repository) {
    $assets = Join-Path $Repository 'dist/riscv'
    $required = @('riscv-packages.json', 'march-extensions.json', 'THIRD_PARTY_NOTICES.txt', 'licenses/CC-BY-4.0.txt')
    $required += @('manual', 'aia', 'debug', 'spmp') | ForEach-Object { "packages/$_.zip" }
    foreach ($name in $required) {
        if (-not (Test-Path -LiteralPath (Join-Path $assets $name) -PathType Leaf)) {
            throw "Missing generated asset: $name. Run tools/build_data.py in the data repository first."
        }
    }
    return $assets
}
