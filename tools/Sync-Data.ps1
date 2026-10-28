param([string]$DataDirectory, [string]$GradleExecutable, [string]$GradleUserHome, [switch]$Offline)
. (Join-Path $PSScriptRoot 'Data-Common.ps1')
$repository = Get-DataRepository $DataDirectory
$assets = Get-DataAssets $repository
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $projectRoot
try {
    Invoke-DataGradle $GradleExecutable $GradleUserHome $Offline.IsPresent @("-PofficialDataDirectory=$assets", ':core:validateOfficialPackages')
    $index = Get-Content -LiteralPath (Join-Path $assets 'riscv-packages.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $manualIndex = [ordered]@{ schemaVersion = $index.schemaVersion; packages = @($index.packages | Where-Object { $_.id -eq 'manual' }) }
    if ($manualIndex.packages.Count -ne 1) { throw 'Exactly one Manual base package is required.' }
    $temporaryIndex = Join-Path ([IO.Path]::GetTempPath()) ('isatlas-index-' + [guid]::NewGuid().ToString('N') + '.json')
    [IO.File]::WriteAllText($temporaryIndex, (($manualIndex | ConvertTo-Json -Depth 12).Replace("`r`n", "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    $copies = [ordered]@{}
    foreach ($file in Get-ChildItem -LiteralPath $assets -File -Recurse) {
        $relative = [IO.Path]::GetRelativePath($assets, $file.FullName).Replace('\', '/')
        if ($relative -notmatch '^(march-extensions\.json|riscv-packages\.json|THIRD_PARTY_NOTICES\.txt|licenses/[A-Za-z0-9.+-]+\.txt|packages/(manual|aia|debug|spmp)\.zip)$') {
            throw "Unexpected generated asset: $relative"
        }
        if ($relative -notmatch '^packages/(aia|debug|spmp)\.zip$') {
            $copies["app/src/main/assets/$relative"] = if ($relative -eq 'riscv-packages.json') { $temporaryIndex } else { $file.FullName }
        }
    }
    # Complete reviewed optional packs are fixtures, never main APK assets.
    $copies['app/src/androidTest/assets/official-data/riscv-packages.json'] = Join-Path $assets 'riscv-packages.json'
    foreach ($name in @('aia', 'debug', 'spmp')) {
        $copies["app/src/androidTest/assets/official-data/packages/$name.zip"] = Join-Path $assets "packages/$name.zip"
    }
    $copies['core/src/test/resources/riscv-manual-sample.json'] = Join-Path $repository 'dist/test-fixtures/riscv-manual-sample.json'
    foreach ($name in @('demo-combined.zip', 'thead-csr.zip')) {
        $copies["app/src/androidTest/assets/$name"] = Join-Path $repository "examples/local-packages/$name"
    }
    # Verify every source before changing the tracked baseline.
    $hashes = @{}
    foreach ($relative in $copies.Keys) { $hashes[$relative] = (Get-FileHash -LiteralPath $copies[$relative] -Algorithm SHA256).Hash.ToLowerInvariant() }
    foreach ($relative in $copies.Keys) {
        $destination = [IO.Path]::GetFullPath((Join-Path $projectRoot $relative))
        if (-not $destination.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Destination escapes the application repository.' }
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
        Copy-Item -LiteralPath $copies[$relative] -Destination $destination
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hashes[$relative]) { throw "Copy verification failed: $relative" }
    }
    foreach ($name in @('aia', 'debug', 'spmp')) {
        $obsolete = Join-Path $projectRoot "app/src/main/assets/packages/$name.zip"
        if (Test-Path -LiteralPath $obsolete -PathType Leaf) { Remove-Item -LiteralPath $obsolete }
    }
    $revision = & git -C $repository rev-parse --verify -q HEAD
    if ($LASTEXITCODE -ne 0) { $revision = $null }
    $dirty = & git -C $repository status --porcelain --ignore-submodules=all
    $migration = Get-Content -LiteralPath (Join-Path $repository 'MIGRATION.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $lock = [ordered]@{
        schemaVersion = 1
        repository = 'https://github.com/noionion/ISAtlas-data'
        revision = $revision
        workingTree = [bool]$dirty
        migrationOrigin = [ordered]@{ repository = $migration.sourceRepository; revision = $migration.sourceCommit }
        packages = @($manualIndex.packages | ForEach-Object { [ordered]@{ id = $_.id; version = $_.version; sha256 = $_.sha256; sourceRevision = $_.source.revision } })
        files = @($copies.Keys | Sort-Object | ForEach-Object { [ordered]@{ path = $_; sha256 = $hashes[$_] } })
    }
    $path = Join-Path $projectRoot 'data/builtin-data.lock.json'
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
    [IO.File]::WriteAllText($path, (($lock | ConvertTo-Json -Depth 12).Replace("`r`n", "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    Write-Output 'Baseline assets and fixtures synchronized; review data/builtin-data.lock.json before committing.'
} finally {
    if ($temporaryIndex -and (Test-Path -LiteralPath $temporaryIndex -PathType Leaf)) { Remove-Item -LiteralPath $temporaryIndex }
    Pop-Location
}
