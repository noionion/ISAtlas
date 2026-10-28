$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lock = Get-Content -LiteralPath (Join-Path $root 'data/builtin-data.lock.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($lock.schemaVersion -ne 1) { throw 'Unsupported baseline lock format.' }
$index = Get-Content -LiteralPath (Join-Path $root 'app/src/main/assets/riscv-packages.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if (@($index.packages).Count -ne 1 -or $index.packages[0].id -ne 'manual' -or -not $index.packages[0].required) { throw 'The default APK must bundle only the Manual base package.' }
if (Get-ChildItem -LiteralPath (Join-Path $root 'app/src/main/assets/packages') -File | Where-Object { $_.Name -ne 'manual.zip' }) { throw 'Unexpected optional archive in main APK assets.' }
foreach ($entry in $lock.files) {
    $path = [IO.Path]::GetFullPath((Join-Path $root $entry.path))
    if (-not $path.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid lock path.' }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) { throw "Baseline hash mismatch: $($entry.path)" }
}
Write-Output "Baseline verified: $($lock.files.Count) files."
