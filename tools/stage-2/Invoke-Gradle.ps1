param([Parameter(Mandatory)][string]$RunId,[Parameter(Mandatory)][string[]]$Tasks)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace=Split-Path (Split-Path $repoRoot -Parent) -Parent
$env:JAVA_HOME=Join-Path $workspace '.tools/jdk17-unpack/jdk-17.0.20.1+1'
$env:ANDROID_HOME=Join-Path $workspace '.tools/android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:ANDROID_USER_HOME=Join-Path $workspace '.private/android-user'
Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue
$env:GRADLE_USER_HOME=Join-Path $workspace '.cache/gradle'
$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
foreach($name in @('SCHOOL_HELPER_KEYSTORE_FILE','SCHOOL_HELPER_KEYSTORE_PASSWORD','SCHOOL_HELPER_KEY_ALIAS','SCHOOL_HELPER_KEY_PASSWORD')){
    [Environment]::SetEnvironmentVariable($name,$null,'Process')
}
$logRoot=Join-Path $repoRoot 'docs/stage-2/evidence/builds'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$logPath=Join-Path $logRoot ($RunId+'.log')
$resultPath=Join-Path $logRoot ($RunId+'.json')
if(Test-Path $resultPath){throw 'Use a new RunId to preserve evidence'}
$arguments=@('--no-daemon','--console=plain','--stacktrace','--max-workers=4')+$Tasks
$deviceIds=@()
if(($Tasks -join ' ') -match 'connected'){
    $deviceIds=@(& (Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe') devices 2>$null |
        Where-Object {$_ -match '^\S+\s+device$'} | ForEach-Object {($_ -split '\s+')[0]})
}
$started=[DateTimeOffset]::Now
$metadata=[ordered]@{runId=$RunId;started=$started.ToString('o');commit=(& git -c "safe.directory=$repoRoot" -C $repoRoot rev-parse HEAD).Trim();command=('.\gradlew.bat '+($arguments -join ' '));javaHome=$env:JAVA_HOME;sdk=$env:ANDROID_HOME}
$metadata|ConvertTo-Json -Depth 4|Set-Content $resultPath -Encoding utf8
Push-Location (Join-Path $repoRoot 'app')
try {
    $ErrorActionPreference='Continue'
    & .\gradlew.bat @arguments 2>&1|ForEach-Object {
        $line="$_";foreach($deviceId in $deviceIds){$line=$line.Replace($deviceId,'[device]')};$line
    }|Tee-Object -FilePath $logPath|Where-Object {"$_" -match '^> Task.*(FAILED|test|lint|assemble)|^BUILD |^FAILURE:|^e: |tests completed|^\* What went wrong:' -and "$_" -notmatch 'NO-SOURCE|UP-TO-DATE'}
    $buildExit=$LASTEXITCODE
} finally {Pop-Location}
$metadata.finished=[DateTimeOffset]::Now.ToString('o')
$metadata.elapsedSeconds=[math]::Round(([DateTimeOffset]::Now-$started).TotalSeconds,2)
$metadata.exitCode=$buildExit
$metadata.logSha256=(Get-FileHash $logPath).Hash
$metadata|ConvertTo-Json -Depth 4|Set-Content $resultPath -Encoding utf8
Write-Output ('Run '+$RunId+' exit='+$buildExit)
exit $buildExit

