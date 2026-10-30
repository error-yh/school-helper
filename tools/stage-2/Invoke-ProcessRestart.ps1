param([Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_-]{1,40}$')][string]$RunId)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace=Split-Path (Split-Path $root -Parent) -Parent
$adb=Join-Path $workspace '.tools/android-sdk/platform-tools/adb.exe'
$env:ANDROID_SDK_HOME=Join-Path $workspace '.private'
$package='io.github.schoolhelper.app.debug'
$component="$package/com.aggapp.app.ui.MainActivity"
$fixture='Stage2Restart_'+$RunId
$edited=$fixture+' 已编辑'
$output=Join-Path $root "docs/stage-2/evidence/device/$RunId.json"
if(Test-Path -LiteralPath $output){throw 'Evidence already exists'}
$helper=Join-Path $PSScriptRoot 'Invoke-Instrumentation.ps1'
& $helper -RunId ($RunId+'-seed') -TestApk 'app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk' -TestPackage ($package+'.test') -TestClass 'com.aggapp.app.CampusTimetableTest' -PersistencePhase seed -CourseName $fixture
if($LASTEXITCODE -ne 0){throw 'Real UI seed failed; do not claim persistence'}
function Launch-OwnedApp {
    $launch=(& $adb shell am start -W -f 0x10008000 -n $component)-join "`n"
    if($LASTEXITCODE -ne 0 -or $launch -notmatch 'Status: ok'){throw 'Own app launch failed'}
}
function Assert-OwnedFocus {
    $window=(& $adb shell dumpsys window)-join "`n"
    if($window -notmatch ('mCurrentFocus=.*'+[regex]::Escape($package))){throw 'Own window is not focused'}
}
function Wait-ForOwnedCourse {
    for($attempt=0;$attempt -lt 5;$attempt++){
        Assert-OwnedFocus
        $raw=(& $adb exec-out uiautomator dump /dev/tty 2>$null)-join "`n"
        $start=$raw.IndexOf('<?xml');$end=$raw.IndexOf('</hierarchy>')
        if($start -lt 0 -or $end -lt $start){continue}
        [xml]$tree=$raw.Substring($start,$end+12-$start)
        if(@($tree.SelectNodes('//node')|Where-Object {
            $_.GetAttribute('package') -eq $package -and $_.GetAttribute('text') -eq $edited
        }).Count -gt 0){return}
    }
    throw 'Synthetic saved course is not visible in ordinary app process'
}
Launch-OwnedApp
Wait-ForOwnedCourse
$oldProcess=((& $adb shell pidof $package)-join '').Trim()
if([string]::IsNullOrWhiteSpace($oldProcess)){throw 'No ordinary app process'}
& $adb shell am force-stop $package
$stopped=[string]::IsNullOrWhiteSpace(((& $adb shell pidof $package)-join ''))
if(-not $stopped){throw 'Own process did not stop'}
Launch-OwnedApp
$newProcess=((& $adb shell pidof $package)-join '').Trim()
if([string]::IsNullOrWhiteSpace($newProcess) -or $newProcess -eq $oldProcess){throw 'New ordinary process not confirmed'}
Wait-ForOwnedCourse
Assert-OwnedFocus
& $adb exec-out screencap -p > (Join-Path $root "docs/stage-2/evidence/device/$RunId.png")
if($LASTEXITCODE -ne 0){throw 'Own screenshot failed'}
& $helper -RunId ($RunId+'-verify') -TestApk 'app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk' -TestPackage ($package+'.test') -TestClass 'com.aggapp.app.CampusTimetableTest' -PersistencePhase verify -CourseName $fixture
if($LASTEXITCODE -ne 0){throw 'Post-restart real Today/Week verification failed'}
[ordered]@{
    package=$package;manualUiSeedPassed=$true;ordinaryProcessSawCourseBeforeStop=$true
    ownProcessStopped=$stopped;differentOrdinaryProcessConfirmed=$true
    courseVisibleAfterExternalProcessRestart=$true;todayAndWeekVerifiedAfterRestart=$true
    onlyOwnedSyntheticFixtureDeleted=$true;identifiersRetained=$false;passed=$true
    apkSha256=(Get-FileHash (Join-Path $root 'app/app/build/outputs/apk/debug/school-helper-debug-v0.2.0.apk')).Hash
}|ConvertTo-Json|Set-Content -LiteralPath $output -Encoding utf8
'External process restart and real persisted course visibility passed.'
