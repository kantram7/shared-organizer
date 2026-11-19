param([string]$ToolRoot,[string]$RuntimeRoot,[string]$PersonalAssets)
$ErrorActionPreference='Stop'
$repo=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$configPath=Join-Path $repo 'build.local.json'
if(Test-Path -LiteralPath $configPath){
    $config=Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    if(!$ToolRoot){$ToolRoot=$config.toolRoot}
    if(!$RuntimeRoot){$RuntimeRoot=Join-Path $config.runtimeRoot 'winter'}
}
if(!$ToolRoot -or !$RuntimeRoot){throw 'Pass ToolRoot and RuntimeRoot, or configure build.local.json.'}
$names=@('KOTIKI_WINTER_PREVIEW','KOTIKI_AGENT_TEST','KOTIKI_AGENT_ASSETS','KOTIKI_TEST_ASSETS','KOTIKI_MIGRATION_ASSETS','KOTIKI_DEBUG_KEYSTORE')
$saved=@{}
foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$null,'Process')}
try {
    $env:KOTIKI_WINTER_PREVIEW='1'
    if($PersonalAssets){$env:KOTIKI_TEST_ASSETS=(Resolve-Path -LiteralPath $PersonalAssets).Path}
    & (Join-Path $repo 'build-local.ps1') -ToolRoot $ToolRoot -RuntimeRoot $RuntimeRoot -Tasks assembleDebug,testDebugUnitTest,lintDebug,--no-daemon,--max-workers=2
} finally {
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
}
