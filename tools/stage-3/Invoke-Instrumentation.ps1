param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_-]{1,80}$')][string]$RunId,
    [Parameter(Mandatory)][string]$TestApk,
    [Parameter(Mandatory)][string]$TestPackage,
    [string]$AppApk,
    [switch]$UseInstalled,
    [string]$TestClass,
    [ValidateSet('/data/user/0/com.lingion.sleepy.test/files/stage3-private')][string]$SampleRoot,
    [ValidateSet('seed','verify')][string]$PersistencePhase,
    [ValidatePattern('^[\p{L}\p{N}_-]{1,80}$')][string]$CourseName,
    [ValidatePattern('^[0-9]{4}-[0-9]{2}-[0-9]{2}$')][string]$ConfirmedStartDate,
    [string]$PrivatePickerConfig,
    [ValidateSet('rapid','rapid-words','rapid-structure')][string]$ProbeEngine
)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace=Split-Path (Split-Path $repoRoot -Parent) -Parent
$adb=Join-Path $workspace '.tools/android-sdk/platform-tools/adb.exe'
$env:ANDROID_SDK_HOME=Join-Path $workspace '.private'
$deviceIds=@(& $adb devices | Where-Object {$_ -match '^\S+\s+device$'} | ForEach-Object {($_ -split '\s+')[0]})
if($deviceIds.Count -ne 1){throw 'Exactly one authorized device required'}
$evidence=Join-Path $repoRoot 'docs/stage-3/evidence/device'
New-Item -ItemType Directory -Path $evidence -Force|Out-Null
$log=Join-Path $evidence ($RunId+'.log')
$resultFile=Join-Path $evidence ($RunId+'.json')
if((Test-Path $resultFile) -or (Test-Path $log)){throw 'Evidence RunId must be unique'}
if($TestPackage -notmatch '^(io\.github\.schoolhelper\.app\.debug\.test|com\.lingion\.sleepy\.test)$'){
    throw 'Only this project test packages are allowed'
}
$metadata=[ordered]@{runId=$RunId;started=[DateTimeOffset]::Now.ToString('o');testPackage=$TestPackage;
    runner='androidx.test.runner.AndroidJUnitRunner';installationMode='adb install -r; no uninstall or pm clear';
    commit=(& git -c "safe.directory=$repoRoot" -C $repoRoot rev-parse HEAD).Trim()}
$metadata|ConvertTo-Json|Set-Content $resultFile -Encoding utf8
$installations=@()
if($UseInstalled){
    if($AppApk){throw 'UseInstalled verifies only the test package; install the host separately'}
    $expected=(Resolve-Path -LiteralPath (Join-Path $repoRoot $TestApk)).Path
    if(-not $expected.StartsWith($repoRoot+[IO.Path]::DirectorySeparatorChar)){throw 'APK must be in integration workspace'}
    $installedPaths=@(& $adb shell pm path $TestPackage 2>$null)
    if($LASTEXITCODE -ne 0 -or $installedPaths.Count -ne 1 -or $installedPaths[0] -notmatch '^package:(/data/app/.+/base\.apk)\s*$'){
        throw 'Expected installed test package was not found'}
    $deviceApk=$Matches[1]
    $scratch=Join-Path $workspace '.private/stage-3/installed-test'
    New-Item -ItemType Directory -Path $scratch -Force|Out-Null
    $copy=Join-Path $scratch ($RunId+'.apk')
    $pullOutput=(& $adb pull $deviceApk $copy 2>&1)-join "`n"
    if($LASTEXITCODE -ne 0){throw 'Unable to verify installed test APK'}
    $expectedHash=(Get-FileHash -LiteralPath $expected).Hash
    if((Get-FileHash -LiteralPath $copy).Hash -ne $expectedHash){throw 'Installed test APK differs from the expected build'}
    # This exact run-owned APK copy is only for SHA verification; no input data.
    Remove-Item -LiteralPath $copy -Force
    $metadata.installationMode='existing test APK pulled privately and SHA256 verified; no install or clear'
    $installations+= [ordered]@{apk=[IO.Path]::GetFileName($expected);sha256=$expectedHash;installedHashMatched=$true}
    $metadata.installations=$installations
}
foreach($apk in @($AppApk,$TestApk)|Where-Object {-not $UseInstalled -and -not [string]::IsNullOrWhiteSpace($_)}){
    $resolved=(Resolve-Path -LiteralPath (Join-Path $repoRoot $apk)).Path
    if(-not $resolved.StartsWith($repoRoot+[IO.Path]::DirectorySeparatorChar)) {throw 'APK must be in integration workspace'}
    $install=(& $adb install --no-streaming -r $resolved 2>&1)-join "`n"
    $installExit=$LASTEXITCODE
    foreach($deviceId in $deviceIds){$install=$install.Replace($deviceId,'[device]')}
    $installations+=[ordered]@{apk=[IO.Path]::GetFileName($resolved);sha256=(Get-FileHash -LiteralPath $resolved).Hash;exit=$installExit;output=$install}
    $metadata.installations=$installations
    if($installExit -ne 0 -or $install -notmatch '(?m)^Success\s*$'){
        $metadata.phase='installation';$metadata.passed=$false;$metadata.tests=$null
        $metadata.finished=[DateTimeOffset]::Now.ToString('o')
        $metadata|ConvertTo-Json -Depth 5|Set-Content $resultFile -Encoding utf8
        Write-Output $install
        throw 'Overlay install failed; no uninstall fallback is permitted'
    }
}
$testTimeout=if($TestClass -eq 'com.aggapp.app.CampusPrivatePdfImportTest'){'600000'}else{'180000'}
$arguments=@('shell','am','instrument','-w','-r','-e','timeout_msec',$testTimeout)
if($TestClass){$arguments+=@('-e','class',$TestClass)}
if($SampleRoot){
    if($TestPackage -ne 'com.lingion.sleepy.test'){throw 'Private sample path is limited to the library test package'}
    $arguments+=@('-e','sampleRoot',$SampleRoot)
}
if($PersistencePhase){
    if(-not $CourseName -or $TestPackage -ne 'io.github.schoolhelper.app.debug.test'){
        throw 'Persistence phases require a synthetic course name and the host test package'
    }
    $arguments+=@('-e','persistencePhase',$PersistencePhase,'-e','courseName',$CourseName)
}
if($ConfirmedStartDate){
    if($TestPackage -ne 'io.github.schoolhelper.app.debug.test'){throw 'Confirmed calendar UI setup is limited to the host test package'}
    $arguments+=@('-e','confirmedStartDate',$ConfirmedStartDate)
}
if($PrivatePickerConfig){
    $isolatedProbe=$TestPackage -eq 'com.lingion.sleepy.test' -and $TestClass -eq 'com.lingion.sleepy.ui.PrivateDocumentSafTest'
    $hostPdfProbe=$TestPackage -eq 'io.github.schoolhelper.app.debug.test' -and $TestClass -eq 'com.aggapp.app.CampusPrivatePdfImportTest'
    if(-not ($isolatedProbe -or $hostPdfProbe)){
        throw 'Private selector is limited to the named isolated document or host PDF tests'
    }
    $privateRoot=[IO.Path]::GetFullPath((Join-Path $workspace '.private'))+[IO.Path]::DirectorySeparatorChar
    $selectorPath=(Resolve-Path -LiteralPath $PrivatePickerConfig).Path
    if(-not $selectorPath.StartsWith($privateRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Selector must remain in workspace private directory'}
    $selector=Get-Content -Raw -LiteralPath $selectorPath | ConvertFrom-Json
    if($selector.alias -notmatch '^[a-z-]{1,32}$' -or -not $selector.displayName -or $selector.displayName.Length -gt 256 -or
        $selector.displayName -match '[\r\n/\\]' ){throw 'Invalid private selector'}
    $encodedSelector=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($selector.displayName))
    $arguments+=@('-e','probeDisplayNameB64',$encodedSelector,'-e','probeAlias',$selector.alias)
    $metadata.privateSelection=[ordered]@{alias=$selector.alias;method='existing device file through system OpenDocument';rawDiagnostic='test package private files; temporary'}
}
if($ProbeEngine){
    if($TestPackage -ne 'com.lingion.sleepy.test'){throw 'Trial OCR engines are limited to the isolated library probe'}
    if(-not $PrivatePickerConfig){throw 'A trial engine is limited to the isolated private document probe'}
    $arguments+=@('-e','probeEngine',$ProbeEngine)
    $metadata.ocrTrial='RapidOCR source library; test only; production loader unchanged'
}
$arguments+=($TestPackage+'/androidx.test.runner.AndroidJUnitRunner')
$metadata.command='adb '+($arguments -join ' ')
if($PrivatePickerConfig){$metadata.command=$metadata.command.Replace($encodedSelector,'[private selector]')}
$metadata|ConvertTo-Json -Depth 5|Set-Content $resultFile -Encoding utf8
$output=@(& $adb @arguments 2>&1|ForEach-Object {
    $line="$_";foreach($deviceId in $deviceIds){$line=$line.Replace($deviceId,'[device]')};$line
}|Tee-Object -FilePath $log)
$nativeExit=$LASTEXITCODE
$joined=$output -join "`n"
$passMatch=[regex]::Match($joined,'OK\s*\((\d+) tests?\)')
$skips=[regex]::Matches($joined,'INSTRUMENTATION_STATUS_CODE:\s*-[34]\s*(?:\r?\n|$)').Count
$failure=($joined -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[12]')
# STATUS_CODE -1 is a test error; the final INSTRUMENTATION_CODE -1 is normal completion.
$passed=$nativeExit -eq 0 -and $passMatch.Success -and [int]$passMatch.Groups[1].Value -gt 0 -and -not $failure -and $skips -eq 0
$metadata.finished=[DateTimeOffset]::Now.ToString('o')
$metadata.nativeExit=$nativeExit
$metadata.skips=$skips;$metadata.passed=$passed;$metadata.logSha256=(Get-FileHash $log).Hash
$metadata.startedTests=[regex]::Matches($joined,'INSTRUMENTATION_STATUS_CODE:\s*1\s*(?:\r?\n|$)').Count
$metadata.completedTests=[regex]::Matches($joined,'INSTRUMENTATION_STATUS_CODE:\s*(?:0|-[1234])\s*(?:\r?\n|$)').Count
$failedSummary=[regex]::Match($joined,'Tests run:\s*(\d+),\s*Failures:\s*(\d+)')
$metadata.executedTests=if($passMatch.Success){[int]$passMatch.Groups[1].Value}elseif($failedSummary.Success){[int]$failedSummary.Groups[1].Value}else{$metadata.completedTests}
$metadata.incompleteTests=[math]::Max(0,$metadata.startedTests-$metadata.completedTests)
$metadata.aborted=(-not $passMatch.Success -and -not $failedSummary.Success)
$metadata.tests=$metadata.executedTests
$metadata.failedTests=if($failedSummary.Success){[int]$failedSummary.Groups[2].Value}else{[regex]::Matches($joined,'INSTRUMENTATION_STATUS_CODE: -[12]').Count}
$metadata|ConvertTo-Json -Depth 5|Set-Content $resultFile -Encoding utf8
Write-Output "Instrumentation $RunId passed=$passed tests=$($metadata.tests) skips=$skips"
if(-not $passed){$output|Select-Object -Last 25;exit 1}

