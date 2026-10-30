param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_-]{1,32}$')][string]$RunPrefix,
    [Parameter(Mandatory)][string]$TestApk,
    [string]$AppApk,
    [switch]$UseInstalled,
    [ValidateSet('pdf-a','pdf-b','pdf-c','image-list','image-grid','image-weeks')][string[]]$Aliases=@('pdf-a','pdf-b','pdf-c')
)
$ErrorActionPreference='Stop'
$repo=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace=Split-Path (Split-Path $repo -Parent) -Parent
$adb=Join-Path $workspace '.tools/android-sdk/platform-tools/adb.exe'
$private=Join-Path $workspace '.private/stage-3/ui'
$evidence=Join-Path $repo 'docs/stage-3/evidence/device'
$package='io.github.schoolhelper.app.debug'
function CurrentDevice {
    $rows=@(& $adb devices | Where-Object {$_ -match '^\S+\s+device$'})
    if($rows.Count -ne 1){throw 'Exactly one authorized device required'}
    return ($rows[0] -split '\s+')[0]
}
$device=CurrentDevice
function RequireSameDevice {if((CurrentDevice) -ne $device){throw 'Connected device changed; preserve checkpoint'}}
function RequirePassed([string]$id) {
    $result=Get-Content -Raw -LiteralPath (Join-Path $evidence ($id+'.json'))|ConvertFrom-Json
    if(-not $result.passed -or $result.tests -ne 1 -or $result.skips -ne 0){throw 'Private PDF acceptance phase failed; preserve its evidence'}
}
function RecoverPrivateDiagnostic([string]$alias,[string]$id) {
    foreach($suffix in @('error.txt','ui-private.json','review-private.json','phase.txt','saved-fields-private.json')) {
        $name="$alias-host-$suffix"
        $raw=@(& $adb shell run-as $package cat "files/stage3-private-probe/$name" 2>$null)
        if($LASTEXITCODE -eq 0){[IO.File]::WriteAllText((Join-Path $private "$id-$name"),($raw -join "`n"),[Text.UTF8Encoding]::new($false))}
    }
}
$first=$true
foreach($alias in $Aliases) {
    RequireSameDevice
    # Remove only old diagnostics for this alias, never its ownership checkpoint.
    # A timed-out runner may not replace error.txt; stale errors must not be
    # mistaken for this run's current failure.
    & $adb shell run-as $package rm -f "files/stage3-private-probe/$alias-host-error.txt" "files/stage3-private-probe/$alias-host-ui-private.json" "files/stage3-private-probe/$alias-host-review-private.json" "files/stage3-private-probe/$alias-host-phase.txt"
    if($LASTEXITCODE -ne 0){throw 'Unable to prepare owned diagnostic files'}
    $seed="$RunPrefix-$alias-seed"
    $verify="$RunPrefix-$alias-verify"
    $install=@{}
    if($AppApk){$install.AppApk=$AppApk}elseif($UseInstalled -or -not $first){$install.UseInstalled=$true}
    try {
        & (Join-Path $PSScriptRoot 'Invoke-Instrumentation.ps1') -RunId $seed -TestApk $TestApk -TestPackage ($package+'.test') -TestClass 'com.aggapp.app.CampusPrivatePdfImportTest' -PersistencePhase seed -CourseName $alias -PrivatePickerConfig (Join-Path $private "$alias-selector.json") @install
        RequirePassed $seed
    } catch {RecoverPrivateDiagnostic $alias $seed;throw}
    $AppApk=$null
    $first=$false
    RequireSameDevice
    $running=@(& $adb shell pidof $package 2>$null)
    & $adb shell am force-stop $package
    if($LASTEXITCODE -ne 0){throw 'External stop failed; preserve checkpoint'}
    $after=@(& $adb shell pidof $package 2>$null)
    $stopFile=Join-Path $evidence ("$RunPrefix-$alias-external-stop.json")
    if(Test-Path -LiteralPath $stopFile){throw 'Evidence ID already exists'}
    [ordered]@{alias=$alias;sameDevice=$true;externalForceStopIssued=$true;wasRunningBefore=($running.Count -gt 0);processAbsentBeforeVerify=($after.Count -eq 0)}|
        ConvertTo-Json|Set-Content -LiteralPath $stopFile -Encoding utf8
    if($after.Count -ne 0){throw 'Host process still running; preserve checkpoint'}
    try {
        & (Join-Path $PSScriptRoot 'Invoke-Instrumentation.ps1') -RunId $verify -TestApk $TestApk -TestPackage ($package+'.test') -TestClass 'com.aggapp.app.CampusPrivatePdfImportTest' -PersistencePhase verify -CourseName $alias -UseInstalled
        RequirePassed $verify
    } catch {RecoverPrivateDiagnostic $alias $verify;throw}
    $raw=@(& $adb shell run-as $package cat "files/stage3-private-probe/$alias-host-result.json")
    if($LASTEXITCODE -ne 0){throw 'Private PDF result missing'}
    $result=($raw -join "`n")|ConvertFrom-Json
    if($result.alias -ne $alias -or -not $result.externalProcessRestartMatched -or -not $result.dailyAndWeeklyVisible){throw 'Unexpected result; preserve diagnostics'}
    # Whitelist anonymous result fields. Never copy the original filename or course text publicly.
    [ordered]@{alias=$alias;sourceArchives=$result.sourceArchives;sourceArrangements=$result.sourceArrangements;newCourses=$result.newCourses;externalProcessRestartMatched=$true;dailyAndWeeklyVisible=$true;verificationPassed=$true}|
        ConvertTo-Json|Set-Content -LiteralPath (Join-Path $evidence "$RunPrefix-$alias-summary.json") -Encoding utf8
    & $adb shell run-as $package rm -f "files/stage3-private-probe/$alias-host-result.json" "files/stage3-private-probe/$alias-host-error.txt" "files/stage3-private-probe/$alias-host-ui-private.json" "files/stage3-private-probe/$alias-host-review-private.json" "files/stage3-private-probe/$alias-host-phase.txt" "files/stage3-private-probe/$alias-host-saved-fields-private.json" files/stage3-private-probe/review-scroll-private.json files/stage3-private-probe/review-action-private.json
    if($LASTEXITCODE -ne 0){throw 'Owned diagnostic cleanup failed'}
    Write-Output "${alias}: real SAF, save, cold restart, daily/week view and owned-course cleanup passed"
}
