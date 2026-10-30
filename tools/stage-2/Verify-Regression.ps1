param([string]$RunId='regression')
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace=Split-Path (Split-Path $root -Parent) -Parent
$baseline='e5245ed6a7cbc5a60b139dc4d9acda23b13bc2a9'
function RepoGit([string[]]$Arguments) { & git -c "safe.directory=$root" -C $root @Arguments }
$platformChanges=@(RepoGit @('diff','--name-only',$baseline,'--','app/core','app/platform'))
$expectedTest='app/platform/taobao/src/test/kotlin/com/aggapp/platform/taobao/MtH5SignerTest.kt'
$unexpected=@($platformChanges|Where-Object {$_ -ne $expectedTest})
$protected=@(
    'app/app/src/main/kotlin/com/aggapp/app/AggApp.kt',
    'app/app/src/main/kotlin/com/aggapp/app/di/PlatformRegistryModule.kt',
    'app/app/src/main/kotlin/com/aggapp/app/di/SessionRepository.kt',
    'app/app/src/main/kotlin/com/aggapp/app/ui/MainViewModel.kt',
    'app/app/src/main/kotlin/com/aggapp/app/ui/ExpressViewModel.kt',
    'app/app/src/main/kotlin/com/aggapp/app/ui/ExpressScreen.kt',
    'app/app/src/main/kotlin/com/aggapp/app/ui/IdCodeScreen.kt',
    'app/gradle/libs.versions.toml','app/gradle/wrapper/gradle-wrapper.properties','app/build.gradle.kts'
)
$checks=@($protected|ForEach-Object {
    $file=$_
    if(-not (Test-Path (Join-Path $root $file))){throw "Protected file missing: $file"}
    $change=@(RepoGit @('diff','--name-only',$baseline,'--',$file))
    [ordered]@{path=$file;unchanged=($change.Count -eq 0);sha256=(Get-FileHash (Join-Path $root $file)).Hash}
})
$sources=@('mashangqu','sleepy')|ForEach-Object {
    $path=Join-Path $workspace "sources/$_"
    $sha=(& git -c "safe.directory=$path" -C $path rev-parse HEAD).Trim()
    $dirty=@(& git -c "safe.directory=$path" -C $path status --porcelain)
    $expected=if($_ -eq 'mashangqu'){$baseline}else{'3dafa110f74ac4f32464ce1efddb24197a4091d9'}
    [ordered]@{name=$_;commit=$sha;clean=($dirty.Count -eq 0);fixedCommit=($sha -eq $expected)}
}
$feature=Get-Content (Join-Path $root 'app/feature/timetable/build.gradle.kts') -Raw
$result=[ordered]@{
    baseline=$baseline
    baselineTagUnchanged=((RepoGit @('rev-parse','baseline/mashangqu-stage-0')).Trim() -eq $baseline)
    stage0BranchUnchanged=((RepoGit @('rev-parse','codex/stage-0')).Trim() -eq '567c19bd7ce6c501023536a43a5ff9366cb2b977')
    stage1BranchUnchanged=((RepoGit @('rev-parse','codex/stage-1')).Trim() -eq '350a0167d4dfa4edcf5e86b6149627e206cbcee6')
    stage1TagObjectUnchanged=((RepoGit @('rev-parse','baseline/school-helper-stage-1')).Trim() -eq '6af21f46de74aec7603d4dc6784d158635cef09f')
    stage1TagUnchanged=((RepoGit @('rev-parse','baseline/school-helper-stage-1^{commit}')).Trim() -eq '350a0167d4dfa4edcf5e86b6149627e206cbcee6')
    corePlatformChanges=$platformChanges;unexpectedCorePlatformChanges=$unexpected
    protectedFiles=$checks;sources=$sources
    noFeaturePlatformDependency=($feature -notmatch 'project\s*\(\s*":(?:app|platform|core:storage)')
}
$passed=$result.baselineTagUnchanged -and $result.stage0BranchUnchanged -and $result.stage1BranchUnchanged -and $result.stage1TagObjectUnchanged -and $result.stage1TagUnchanged -and $unexpected.Count -eq 0 -and
    @($checks|Where-Object {-not $_.unchanged}).Count -eq 0 -and
    @($sources|Where-Object {-not $_.clean -or -not $_.fixedCommit}).Count -eq 0 -and $result.noFeaturePlatformDependency
$result.passed=$passed
$result|ConvertTo-Json -Depth 8|Set-Content (Join-Path $root "docs/stage-2/evidence/$RunId.json") -Encoding utf8
Write-Output "Regression source protection passed=$passed; protected host/build files=$($checks.Count); unexpected core/platform changes=$($unexpected.Count)"
if(-not $passed){exit 1}

