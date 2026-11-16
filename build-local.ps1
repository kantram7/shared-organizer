param(
    [string]$ToolRoot,
    [string]$RuntimeRoot,
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug')
)
$ErrorActionPreference = 'Stop'
$configPath = Join-Path $PSScriptRoot 'build.local.json'
if (Test-Path -LiteralPath $configPath) {
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    if (!$ToolRoot) { $ToolRoot = $config.toolRoot }
    if (!$RuntimeRoot) { $RuntimeRoot = $config.runtimeRoot }
}
$envNames = @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_USER_HOME', 'GRADLE_USER_HOME', 'JAVA_TOOL_OPTIONS', 'KOTIKI_RUNTIME_DIR', 'PATH')
$savedEnvironment = @{}
foreach ($name in $envNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $localRoot = if ($RuntimeRoot) { [IO.Path]::GetFullPath($RuntimeRoot) } else { Join-Path $PSScriptRoot '.local' }
    $env:KOTIKI_RUNTIME_DIR = $localRoot
    $javaTemp = Join-Path $localRoot 'java-temp'
    $javaUser = Join-Path $localRoot 'java-user'
    New-Item -ItemType Directory -Force -Path $javaTemp,$javaUser | Out-Null
    $gradle = Join-Path $PSScriptRoot 'gradlew.bat'
    if ($ToolRoot) {
        $ToolRoot = (Resolve-Path -LiteralPath $ToolRoot).Path
        $jdkDirectory = Get-ChildItem -LiteralPath (Join-Path $ToolRoot 'java') -Directory | Sort-Object Name | Select-Object -First 1
        if (!$jdkDirectory) { throw 'No JDK found in ToolRoot/java.' }
        $env:JAVA_HOME = $jdkDirectory.FullName
        $env:ANDROID_HOME = Join-Path $ToolRoot 'android-sdk'
        $env:ANDROID_USER_HOME = Join-Path $ToolRoot 'android-user'
        $env:GRADLE_USER_HOME = Join-Path $ToolRoot 'gradle-cache'
        $gradle = Join-Path $ToolRoot 'gradle-8.13/bin/gradle.bat'
    }
    if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 21 or pass -ToolRoot.' }
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
    $env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + ' "-Djava.io.tmpdir=' + $javaTemp + '" "-Duser.home=' + $javaUser + '"').Trim()
    $projectCache = Join-Path $localRoot 'gradle-project'
    $kotlinCache = Join-Path $localRoot 'kotlin'
    & $gradle --project-dir $PSScriptRoot --project-cache-dir $projectCache "-Pkotlin.project.persistent.dir=$kotlinCache" '-Pkotlin.project.persistent.dir.gradle.disableWrite=true' --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with code $LASTEXITCODE" }
} finally {
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}
