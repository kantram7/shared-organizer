param([string]$EnvironmentRoot)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (!$EnvironmentRoot) {
    $config = Get-Content -LiteralPath (Join-Path $repoRoot 'build.local.json') -Raw | ConvertFrom-Json
    $EnvironmentRoot = Split-Path $config.toolRoot -Parent
}
$EnvironmentRoot = (Resolve-Path -LiteralPath $EnvironmentRoot).Path
$toolRoot = Join-Path $EnvironmentRoot 'tools'
$sdkRoot = Join-Path $toolRoot 'android-sdk'
$avdRoot = Join-Path $EnvironmentRoot 'runtime/avd'
$manager = Join-Path $sdkRoot 'cmdline-tools/19.0/bin/avdmanager.bat'
if (!(Test-Path -LiteralPath $manager)) { throw 'Android command-line tools 19.0 are required for this setup script.' }
$jdk = Get-ChildItem -LiteralPath (Join-Path $toolRoot 'java') -Directory | Sort-Object Name | Select-Object -First 1
if (!$jdk) { throw 'JDK not found.' }
$names = @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_USER_HOME', 'ANDROID_AVD_HOME')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:JAVA_HOME = $jdk.FullName
    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'
    $env:ANDROID_AVD_HOME = $avdRoot
    New-Item -ItemType Directory -Force -Path $avdRoot | Out-Null
    foreach ($number in @(1,2)) {
        $name = "SharedOrganizer_User$number"
        $devicePath = Join-Path $avdRoot "$name.avd"
        if (Test-Path -LiteralPath (Join-Path $devicePath 'config.ini')) {
            Write-Output "$name already exists; preserving its settings and data."
            continue
        }
        if (Test-Path -LiteralPath $devicePath) { throw "Incomplete existing AVD: $devicePath. Inspect it before retrying." }
        'no' | & $manager create avd --name $name --package 'system-images;android-36;google_apis;x86_64' --device pixel_5 --path $devicePath
        if ($LASTEXITCODE -ne 0) { throw "AVD creation failed: $name" }
        $configPath = Join-Path $devicePath 'config.ini'
        $settings = @{}
        foreach ($line in Get-Content -LiteralPath $configPath) {
            if ($line -match '^([^#=]+)=(.*)$') { $settings[$matches[1].Trim()] = $matches[2] }
        }
        $settings['hw.ramSize'] = '2048'
        $settings['hw.cpu.ncore'] = '2'
        $settings['vm.heapSize'] = '256'
        $settings['disk.dataPartition.size'] = '4G'
        $settings['hw.keyboard'] = 'yes'
        $settings['hw.gpu.enabled'] = 'yes'
        $settings['hw.gpu.mode'] = 'auto'
        $settings['showDeviceFrame'] = 'no'
        $settings['avd.ini.displayname'] = "Shared Organizer - User $number"
        $lines = $settings.Keys | Sort-Object | ForEach-Object { "$_=$($settings[$_])" }
        [IO.File]::WriteAllLines($configPath, $lines, [Text.UTF8Encoding]::new($false))
        Write-Output "Created $name with independent storage."
    }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
