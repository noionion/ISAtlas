param([string[]]$Tasks = @(':core:test', ':app:assembleDebug', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
if (-not $env:JAVA_HOME -and (Test-Path 'C:\Program Files\Java\jdk-21')) {
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 installation.' }
$env:GRADLE_USER_HOME = Join-Path $root '.tools/gradle-user-home'
Push-Location $root
try {
    $localGradle = Join-Path $root '.tools/gradle-8.11.1/bin/gradle.bat'
    $launcher = if (Test-Path $localGradle) { $localGradle } else { Join-Path $root 'gradlew.bat' }
    & $launcher @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with code $LASTEXITCODE" }
} finally { Pop-Location }
