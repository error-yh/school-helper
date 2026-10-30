param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$TestApk,
    [Parameter(Mandatory)][string]$TestPackage,
    [string]$AppApk,
    [string]$TestClass,
    [ValidateSet('seed','verify')][string]$PersistencePhase,
    [ValidatePattern('^[\p{L}\p{N}_-]{1,80}$')][string]$CourseName
)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace=Split-Path (Split-Path $repoRoot -Parent) -Parent
$adb=Join-Path $workspace '.tools/android-sdk/platform-tools/adb.exe'
$env:ANDROID_SDK_HOME=Join-Path $workspace '.private'
$deviceIds=@(& $adb devices | Where-Object {$_ -match '^\S+\s+device$'} | ForEach-Object {($_ -split '\s+')[0]})
if($deviceIds.Count -ne 1){throw 'Exactly one authorized device required'}
$evidence=Join-Path $repoRoot 'docs/stage-2/evidence/device'
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
foreach($apk in @($AppApk,$TestApk)|Where-Object {-not [string]::IsNullOrWhiteSpace($_)}){
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
$arguments=@('shell','am','instrument','-w','-r','-e','timeout_msec','180000')
if($TestClass){$arguments+=@('-e','class',$TestClass)}
if($PersistencePhase){
    if(-not $CourseName -or $TestPackage -ne 'io.github.schoolhelper.app.debug.test'){
        throw 'Persistence phases require a synthetic course name and the host test package'
    }
    $arguments+=@('-e','persistencePhase',$PersistencePhase,'-e','courseName',$CourseName)
}
$arguments+=($TestPackage+'/androidx.test.runner.AndroidJUnitRunner')
$metadata.command='adb '+($arguments -join ' ')
$metadata|ConvertTo-Json -Depth 5|Set-Content $resultFile -Encoding utf8
$output=@(& $adb @arguments 2>&1|ForEach-Object {
    $line="$_";foreach($deviceId in $deviceIds){$line=$line.Replace($deviceId,'[device]')};$line
}|Tee-Object -FilePath $log)
$nativeExit=$LASTEXITCODE
$joined=$output -join "`n"
$passMatch=[regex]::Match($joined,'OK\s*\((\d+) tests?\)')
$skips=[regex]::Matches($joined,'INSTRUMENTATION_STATUS_CODE: -3').Count
$failure=($joined -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[12]')
# STATUS_CODE -1 is a test error; the final INSTRUMENTATION_CODE -1 is normal completion.
$passed=$nativeExit -eq 0 -and $passMatch.Success -and [int]$passMatch.Groups[1].Value -gt 0 -and -not $failure -and $skips -eq 0
$metadata.finished=[DateTimeOffset]::Now.ToString('o')
$metadata.nativeExit=$nativeExit;$metadata.tests=if($passMatch.Success){[int]$passMatch.Groups[1].Value}else{0}
$metadata.skips=$skips;$metadata.passed=$passed;$metadata.logSha256=(Get-FileHash $log).Hash
$metadata.startedTests=[regex]::Matches($joined,'INSTRUMENTATION_STATUS_CODE:\s*1\s*(?:\r?\n|$)').Count
$failedSummary=[regex]::Match($joined,'Tests run:\s*(\d+),\s*Failures:\s*(\d+)')
$metadata.executedTests=if($passMatch.Success){[int]$passMatch.Groups[1].Value}elseif($failedSummary.Success){[int]$failedSummary.Groups[1].Value}else{$metadata.startedTests}
$metadata.failedTests=if($failedSummary.Success){[int]$failedSummary.Groups[2].Value}else{[regex]::Matches($joined,'INSTRUMENTATION_STATUS_CODE: -[12]').Count}
$metadata|ConvertTo-Json -Depth 5|Set-Content $resultFile -Encoding utf8
Write-Output "Instrumentation $RunId passed=$passed tests=$($metadata.tests) skips=$skips"
if(-not $passed){$output|Select-Object -Last 25;exit 1}
