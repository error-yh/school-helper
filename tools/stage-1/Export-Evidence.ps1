$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace=Split-Path (Split-Path $root -Parent) -Parent
$evidence=Join-Path $root 'docs/stage-1/evidence'
$testSummary=@()
Get-ChildItem (Join-Path $root 'app') -Recurse -Filter 'TEST-*.xml' | Where-Object {
    $_.FullName -match 'build[\\/]test-results[\\/]test' -and $_.FullName -notmatch 'androidTest'
} | ForEach-Object {
    [xml]$unitXml=Get-Content $_.FullName
    $relative=$_.FullName.Substring($root.Length+1)
    $destination=Join-Path $evidence ('tests/'+$relative.Replace('\','/').Replace('/build/test-results/','/'))
    New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force|Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $destination
    $testSummary += [ordered]@{source=$relative;suite=$unitXml.testsuite.name;tests=[int]$unitXml.testsuite.tests;
        failures=[int]$unitXml.testsuite.failures;errors=[int]$unitXml.testsuite.errors;skipped=[int]$unitXml.testsuite.skipped}
}
$testSummary|ConvertTo-Json -Depth 5|Set-Content (Join-Path $evidence 'unit-tests.json') -Encoding utf8
$executionCount=0
foreach($suite in $testSummary){$executionCount += $suite.tests}
if($testSummary.Count -ne 5 -or $executionCount -ne 32){throw 'Expected all five Stage 1 JVM suite/variant reports (32 result entries)'}
$lintSummary=@()
foreach($module in @('app','feature/timetable')){
    foreach($variant in @('debug','release')){
        $file=Join-Path $root "app/$module/build/reports/lint-results-$variant.xml"
        [xml]$lintXml=Get-Content $file
        $destination=Join-Path $evidence "lint/$module/lint-results-$variant.xml"
        New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force|Out-Null
        Copy-Item -LiteralPath $file -Destination $destination
        $lintSummary += [ordered]@{module=$module;variant=$variant;
            errors=@($lintXml.issues.issue|Where-Object {$_.severity -in @('Error','Fatal')}).Count;
            warnings=@($lintXml.issues.issue|Where-Object {$_.severity -eq 'Warning'}).Count}
    }
}
$lintSummary|ConvertTo-Json|Set-Content (Join-Path $evidence 'lint-summary.json') -Encoding utf8
$env:JAVA_HOME=Join-Path $workspace '.tools/jdk17-unpack/jdk-17.0.20.1+1'
$buildTools=Join-Path $workspace '.tools/android-sdk/build-tools/36.0.0'
$apk=Join-Path $root 'app/app/build/outputs/apk/debug/school-helper-debug-v0.1.0.apk'
$badging=@(& (Join-Path $buildTools 'aapt.exe') dump badging $apk)
$permissions=(& (Join-Path $buildTools 'aapt.exe') dump permissions $apk)-join "`n"
$signature=@(& (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $apk)
if($LASTEXITCODE -ne 0){throw 'APK signature verification failed'}
$signature|Set-Content (Join-Path $evidence 'apk-signature.txt') -Encoding utf8
$badging|Where-Object {$_ -match '^package:|^sdkVersion:|^targetSdkVersion:|^application-label:'} |
    Set-Content (Join-Path $evidence 'apk-badging.txt') -Encoding utf8
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($apk)
try {
    $licenseChecks=@()
    foreach($name in @('MIT-mashangqu.txt','GPL-3.0-sleepy.txt')){
        $entry=$archive.GetEntry("assets/open_source/$name")
        if(-not $entry){throw 'Missing bundled license'}
        $stream=$entry.Open();$buffer=[IO.MemoryStream]::new()
        try {$stream.CopyTo($buffer);$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($buffer.ToArray()))}
        finally {$stream.Dispose();$buffer.Dispose()}
        $licenseChecks += [ordered]@{name=$name;sha256=$hash;matchesSource=($hash -eq (Get-FileHash (Join-Path $root "LICENSES/$name")).Hash)}
    }
    $oldEndpointHits=0
    foreach($entry in $archive.Entries|Where-Object {$_.FullName -match '^classes\d*\.dex$'}){
        $stream=$entry.Open();$buffer=[IO.MemoryStream]::new()
        try {$stream.CopyTo($buffer);$strings=[Text.Encoding]::UTF8.GetString($buffer.ToArray())}
        finally {$stream.Dispose();$buffer.Dispose()}
        $oldEndpointHits += [regex]::Matches($strings,'msq\.(?:l2|12)\.ink|cdn\.jsdelivr\.net/gh/njpemo/mashangqu|raw\.githubusercontent\.com/njpemo/mashangqu').Count
    }
} finally {$archive.Dispose()}
$aar=Join-Path $root 'app/feature/timetable/build/outputs/aar/timetable-debug.aar'
$artifact=[ordered]@{
    codeCommit=(& git -c "safe.directory=$root" -C $root rev-parse HEAD).Trim()
    trackedProductionClean=(@(& git -c "safe.directory=$root" -C $root diff --name-only HEAD -- app LICENSE LICENSES NOTICE.md).Count -eq 0)
    apkPath=$apk;apkBytes=(Get-Item $apk).Length;apkSha256=(Get-FileHash $apk).Hash
    aarPath=$aar;aarSha256=(Get-FileHash $aar).Hash
    package='io.github.schoolhelper.app.debug';versionName='0.1.0';versionCode=1;minSdk=26;compileSdk=36;targetSdk=34
    debugCertificateOnly=(@($signature|Where-Object {$_ -match 'certificate DN:.*CN=Android Debug'}).Count -eq 1)
    hasInstallPackagesPermission=($permissions -match 'android.permission.REQUEST_INSTALL_PACKAGES')
    oldUpdateEndpointHitsInDex=$oldEndpointHits;bundledLicenses=$licenseChecks
    releaseApkExists=(@(Get-ChildItem (Join-Path $root 'app/app/build/outputs/apk/release') -Filter '*.apk' -ErrorAction SilentlyContinue).Count -gt 0)
}
$artifact|ConvertTo-Json -Depth 6|Set-Content (Join-Path $evidence 'artifacts.json') -Encoding utf8
if(@($testSummary|Where-Object {$_.failures -or $_.errors -or $_.skipped}).Count -or
    @($lintSummary|Where-Object {$_.errors}).Count -or $oldEndpointHits -or $artifact.hasInstallPackagesPermission -or
    @($licenseChecks|Where-Object {-not $_.matchesSource}).Count -or -not $artifact.trackedProductionClean){throw 'Evidence gate failed'}
Write-Output "Evidence exported: JVM report entries=$executionCount; lint errors=0; old updater DEX hits=0; APK SHA256=$($artifact.apkSha256)"
