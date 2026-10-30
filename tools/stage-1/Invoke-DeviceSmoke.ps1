$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace=Split-Path (Split-Path $root -Parent) -Parent
$adb=Join-Path $workspace '.tools/android-sdk/platform-tools/adb.exe'
$env:ANDROID_SDK_HOME=Join-Path $workspace '.private'
$package='io.github.schoolhelper.app.debug'
$component="$package/com.aggapp.app.ui.MainActivity"
$apk=Join-Path $root 'app/app/build/outputs/apk/debug/school-helper-debug-v0.1.0.apk'
$evidence=Join-Path $root 'docs/stage-1/evidence/device'
$resultFile=Join-Path $evidence 'process-restart.json'
if(Test-Path $resultFile){throw 'Existing evidence must not be overwritten'}
$devices=@(& $adb devices | Where-Object {$_ -match '^\S+\s+device$'})
if($devices.Count -ne 1){throw 'Exactly one authorized device is required'}
function Assert-OwnedWindow {
    $windows=(& $adb shell dumpsys window)-join "`n"
    if($windows -notmatch ('mCurrentFocus=.*'+[regex]::Escape($package))){throw 'Campus app is not focused; capture/input refused'}
}
function Read-OwnedHierarchy {
    Assert-OwnedWindow
    $dump=(& $adb exec-out uiautomator dump /dev/tty 2>$null)-join "`n"
    $start=$dump.IndexOf('<?xml');$end=$dump.IndexOf('</hierarchy>')
    if($start -lt 0 -or $end -lt $start){throw 'No accessibility hierarchy'}
    [xml]$tree=$dump.Substring($start,$end+12-$start)
    return ,$tree
}
function Find-OwnedText([xml]$Tree,[string]$Text) {
    @($Tree.SelectNodes('//node')|Where-Object {
        $_.GetAttribute('package') -eq $package -and $_.GetAttribute('text') -eq $Text
    })
}
$install=(& $adb install --no-streaming -r $apk 2>&1)-join "`n"
if($LASTEXITCODE -ne 0 -or $install -notmatch '(?m)^Success\s*$'){throw 'Debug APK install failed'}
$launch=(& $adb shell am start -W -n $component)-join "`n"
if($launch -notmatch 'Status: ok'){throw 'App launch failed'}
$tree=Read-OwnedHierarchy
$button=@(Find-OwnedText $tree '添加示例课程')|Select-Object -First 1
if(-not $button){throw 'Sample course action not visible'}
while($button.GetAttribute('clickable') -ne 'true'){
    $button=$button.ParentNode
    if($button.Name -ne 'node'){throw 'No clickable sample course control'}
}
if($button.GetAttribute('enabled') -ne 'true'){throw 'Sample course action is disabled'}
$bounds=[regex]::Match($button.GetAttribute('bounds'),'\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
if(-not $bounds.Success){throw 'Invalid control bounds'}
$tapX=[int](([int]$bounds.Groups[1].Value+[int]$bounds.Groups[3].Value)/2)
$tapY=[int](([int]$bounds.Groups[2].Value+[int]$bounds.Groups[4].Value)/2)
Assert-OwnedWindow
& $adb shell input tap $tapX $tapY
$saved=$false
for($attempt=0;$attempt -lt 5;$attempt++){
    if(@(Find-OwnedText (Read-OwnedHierarchy) '示例课程').Count -gt 0){$saved=$true;break}
}
if(-not $saved){throw 'Saved course did not appear'}
$oldPid=((& $adb shell pidof $package)-join '').Trim()
& $adb shell am force-stop $package
$stopped=([string]::IsNullOrWhiteSpace(((& $adb shell pidof $package)-join '')))
if(-not $stopped){throw 'Own process did not stop'}
$restart=(& $adb shell am start -W -n $component)-join "`n"
if($restart -notmatch 'Status: ok'){throw 'Restart failed'}
$newPid=((& $adb shell pidof $package)-join '').Trim()
$persisted=@(Find-OwnedText (Read-OwnedHierarchy) '示例课程').Count -gt 0
if(-not $persisted -or $newPid -eq $oldPid -or [string]::IsNullOrWhiteSpace($newPid)){
    throw 'Process restart persistence check failed'
}
Assert-OwnedWindow
& $adb exec-out screencap -p > (Join-Path $evidence 'process-restart.png')
$result=[ordered]@{
    date=[DateTimeOffset]::Now.ToString('o');package=$package
    apkSha256=(Get-FileHash $apk).Hash;installSucceeded=$true
    sampleAddedViaRealUi=$saved;ownProcessStopped=$stopped;newProcessConfirmed=($oldPid -ne $newPid)
    courseVisibleAfterProcessRestart=$persisted;passed=$true
    limitations='Synthetic course only; no real platform login or airplane-mode test; no device identifiers retained.'
}
$result|ConvertTo-Json|Set-Content $resultFile -Encoding utf8
Write-Output 'Device smoke: install, real UI add, process stop/restart and persisted course visibility passed.'
