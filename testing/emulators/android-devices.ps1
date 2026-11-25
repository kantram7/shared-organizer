param(
    [ValidateSet('Check', 'Status', 'Start', 'Install', 'Stop')][string]$Action = 'Status',
    [ValidateSet('User1', 'User2', 'All')][string]$Device = 'All',
    [string]$EnvironmentRoot,
    [string]$ApkPath,
    [switch]$Headless,
    [switch]$ColdBoot,
    [ValidateSet('auto', 'host', 'software', 'swiftshader')][string]$GpuMode = 'auto',
    [switch]$DisableVulkan,
    [string]$TimeZone,
    [ValidateRange(1024,4096)][int]$MemoryMiB = 2048,
    [switch]$ResetUserData,
    [switch]$AllowLowMemory
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (!$EnvironmentRoot) {
    $configPath = Join-Path $repoRoot 'build.local.json'
    if (!(Test-Path -LiteralPath $configPath)) { throw 'Pass -EnvironmentRoot or configure build.local.json.' }
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $EnvironmentRoot = Split-Path $config.toolRoot -Parent
}
$EnvironmentRoot = (Resolve-Path -LiteralPath $EnvironmentRoot).Path
$sdkRoot = Join-Path $EnvironmentRoot 'tools/android-sdk'
$adb = Join-Path $sdkRoot 'platform-tools/adb.exe'
$emulator = Join-Path $sdkRoot 'emulator/emulator.exe'
$avdRoot = Join-Path $EnvironmentRoot 'runtime/avd'
$logRoot = Join-Path $repoRoot 'testing/local/logs/emulators'
$devices = @(
    [pscustomobject]@{ User = 'User1'; Avd = 'SharedOrganizer_User1'; Port = 5554; Serial = 'emulator-5554' },
    [pscustomobject]@{ User = 'User2'; Avd = 'SharedOrganizer_User2'; Port = 5556; Serial = 'emulator-5556' }
)
if ($Device -ne 'All') { $devices = @($devices | Where-Object User -eq $Device) }
if (!(Test-Path -LiteralPath $adb) -or !(Test-Path -LiteralPath $emulator)) { throw 'Android Emulator or ADB is not installed.' }

function Get-DeviceState($target) {
    # Windows PowerShell turns native stderr into terminating errors with Stop.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = @(& $adb devices 2>$null)
        if ($LASTEXITCODE -ne 0) { throw 'ADB device discovery failed.' }
        $pattern = '^' + [regex]::Escape($target.Serial) + '\s+(\S+)'
        foreach ($line in $result) { if ($line -match $pattern) { return $matches[1] } }
        return 'stopped'
    } finally { $ErrorActionPreference = $previousPreference }
}
function Assert-DeviceIdentity($target) {
    $name = @(& $adb -s $target.Serial emu avd name 2>$null)
    if ($LASTEXITCODE -ne 0 -or $name -notcontains $target.Avd) {
        throw "Port $($target.Port) is not occupied by the expected AVD $($target.Avd)."
    }
}
function Wait-ForDeviceBoot($target) {
    $deadline = [DateTime]::UtcNow.AddMinutes(4)
    do {
        if ((Get-DeviceState $target) -eq 'device') {
            Assert-DeviceIdentity $target
            $boot = & $adb -s $target.Serial shell getprop sys.boot_completed 2>$null
            if (([string]$boot).Trim() -eq '1') { return }
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Boot timed out for $($target.Avd). See $logRoot."
}

$names = @('ANDROID_HOME', 'ANDROID_USER_HOME', 'ANDROID_AVD_HOME')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_USER_HOME = Join-Path $EnvironmentRoot 'tools/android-user'
    $env:ANDROID_AVD_HOME = $avdRoot
    if ($Action -eq 'Check') {
        & $emulator -accel-check
        if ($LASTEXITCODE -ne 0) { throw 'Hardware acceleration is unavailable. Check WHPX and any pending Windows restart.' }
        & $emulator -list-avds
        if ($LASTEXITCODE -ne 0) { throw 'Could not list AVDs.' }
        return
    }
    if ($Action -eq 'Start') {
        & $emulator -accel-check
        if ($LASTEXITCODE -ne 0) { throw 'Hardware acceleration is unavailable. Complete Windows setup/restart first.' }
        if ($ResetUserData -and @($devices | Where-Object { (Get-DeviceState $_) -ne 'stopped' }).Count -gt 0) { throw 'Stop the selected test emulators before resetting their data.' }
        $pending = @($devices | Where-Object { (Get-DeviceState $_) -eq 'stopped' })
        if ($pending.Count -gt 0 -and !$AllowLowMemory) {
            $freeGB = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB
            $requiredGB = 1 + ($MemoryMiB / 1024 + 1) * $pending.Count
            if ($freeGB -lt $requiredGB) {
                throw ('Only {0:N1} GB RAM free; close unused applications to free about {1} GB, or start one device. -AllowLowMemory overrides this check.' -f $freeGB,$requiredGB)
            }
        }
        New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
        foreach ($target in $devices) {
            if ((Get-DeviceState $target) -eq 'device') { Assert-DeviceIdentity $target; Write-Output "$($target.User): already running"; continue }
            if ((Get-DeviceState $target) -ne 'stopped') { throw "$($target.Serial) is offline or not ready; inspect it before starting another instance." }
            $arguments = @('-avd', $target.Avd, '-port', $target.Port, '-memory', $MemoryMiB, '-cores', '2', '-gpu', $GpuMode, '-no-boot-anim')
            if ($ResetUserData) { $arguments += @('-wipe-data','-no-snapshot') }
            if ($TimeZone) { $arguments += @('-timezone', $TimeZone) }
            if ($DisableVulkan) { $arguments += @('-feature', '-Vulkan') }
            if ($Headless) { $arguments += '-no-window' }
            if ($ColdBoot) { $arguments += '-no-snapshot-load' }
            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
            $style = if ($Headless) { 'Hidden' } else { 'Normal' }
            Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle $style -RedirectStandardOutput (Join-Path $logRoot "$($target.User)-$stamp.out.log") -RedirectStandardError (Join-Path $logRoot "$($target.User)-$stamp.err.log") | Out-Null
            Write-Output "$($target.User): waiting for Android to boot..."
            Wait-ForDeviceBoot $target
            Write-Output "$($target.User): ready ($($target.Serial))"
        }
    }
    if ($Action -eq 'Install') {
        if (!$ApkPath) { $ApkPath = Join-Path $EnvironmentRoot 'runtime/build/app/outputs/apk/debug/app-debug.apk' }
        $ApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
        foreach ($target in $devices) {
            if ((Get-DeviceState $target) -ne 'device') { throw "$($target.User) is not running. Start it first." }
            Assert-DeviceIdentity $target
            Wait-ForDeviceBoot $target
            & $adb -s $target.Serial install -r $ApkPath
            if ($LASTEXITCODE -ne 0) { throw "APK installation failed on $($target.User)." }
            & $adb -s $target.Serial shell am start -W -n team.kotiki.app/team.kotiki.app.MainActivity
            if ($LASTEXITCODE -ne 0) { throw "App launch failed on $($target.User)." }
        }
    }
    if ($Action -eq 'Stop') {
        foreach ($target in $devices) {
            if ((Get-DeviceState $target) -eq 'stopped') { continue }
            Assert-DeviceIdentity $target
            & $adb -s $target.Serial emu kill
            if ($LASTEXITCODE -ne 0) { throw "Could not stop $($target.User)." }
        }
    }
    if ($Action -eq 'Status') {
        foreach ($target in $devices) {
            $state = Get-DeviceState $target
            if ($state -eq 'device') { Assert-DeviceIdentity $target }
            [pscustomobject]@{ User = $target.User; AVD = $target.Avd; Serial = $target.Serial; State = $state }
        }
    }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
