param([Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_-]+$')][string]$RunId)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace=Split-Path (Split-Path $repoRoot -Parent) -Parent
$adb=Join-Path $workspace '.tools/android-sdk/platform-tools/adb.exe'
$env:ANDROID_SDK_HOME=Join-Path $workspace '.private'
$devices=@(& $adb devices|Where-Object {$_ -match '^\S+\s+device$'})
if($devices.Count -ne 1){throw 'Exactly one authorized device required'}
$package='io.github.schoolhelper.app.debug'
$privateRoot=[IO.Path]::GetFullPath((Join-Path $workspace '.private/stage-2-backup'))
$inspection=[IO.Path]::GetFullPath((Join-Path $privateRoot $RunId))
if(-not $inspection.StartsWith($privateRoot+[IO.Path]::DirectorySeparatorChar)){throw 'Private path check failed'}
if(Test-Path -LiteralPath $inspection){throw 'Use a unique RunId; never overwrite a backup'}
New-Item -ItemType Directory -Path $inspection|Out-Null
$archive=Join-Path $privateRoot ($RunId+'.tar')
if(Test-Path -LiteralPath $archive){throw 'Backup already exists'}
& $adb shell am force-stop $package
if($LASTEXITCODE -ne 0){throw 'Unable to stop own app'}
& $adb exec-out run-as $package tar -cf - databases shared_prefs/timetable_local_profile.xml > $archive
if($LASTEXITCODE -ne 0){throw 'Private timetable backup failed'}
$entries=@(& tar -tf $archive)
if($LASTEXITCODE -ne 0){throw 'Backup archive invalid'}
foreach($entry in $entries){
    if($entry -match '(^[/\\]|(^|[/\\])\.\.([/\\]|$)|:)' -or $entry -notmatch '^(databases(/|$)|shared_prefs/timetable_local_profile\.xml$)'){
        throw 'Refuse unsafe archive entry'
    }
}
& tar -xf $archive -C $inspection
if($LASTEXITCODE -ne 0){throw 'Private backup extraction failed'}
$evidence=Join-Path $repoRoot "docs/stage-2/evidence/$RunId.json"
if(Test-Path -LiteralPath $evidence){throw 'Evidence already exists'}
& node (Join-Path $PSScriptRoot 'Verify-Upgrade.cjs') $inspection (Join-Path $repoRoot 'docs/stage-2/evidence/pre-upgrade-state.json') $evidence
if($LASTEXITCODE -ne 0){throw 'Upgrade verification failed; original backup retained'}
