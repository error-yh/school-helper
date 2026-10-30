$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location $root
try {
    $target='docs/stage-2/evidence'
    $unit=@(rg --files --hidden --no-ignore -g 'TEST-*.xml' app | Where-Object {$_ -match '[/\\]build[/\\]test-results[/\\]test[^/\\]*[/\\]'})
    $results=@($unit|ForEach-Object {
        [xml]$xml=Get-Content -LiteralPath $_ -Raw
        $destination=Join-Path "$target/final-unit" $_
        New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force|Out-Null
        Copy-Item -LiteralPath $_ -Destination $destination
        [ordered]@{path=$_;suite=$xml.testsuite.name;tests=[int]$xml.testsuite.tests;
            failures=[int]$xml.testsuite.failures;errors=[int]$xml.testsuite.errors;skipped=[int]$xml.testsuite.skipped}
    })
    $results|ConvertTo-Json -Depth 5|Set-Content "$target/unit-summary.json" -Encoding utf8
    $total=0;foreach($result in $results){$total+=$result.tests;if($result.failures -or $result.errors -or $result.skipped){throw 'Unit report contains failures/errors/skips'}}
    $lint=@(Get-ChildItem app/app/build/reports/lint-results-*.xml,app/feature/timetable/build/reports/lint-results-*.xml|ForEach-Object {
        [xml]$xml=Get-Content -LiteralPath $_.FullName -Raw
        $relative=[IO.Path]::GetRelativePath($root,$_.FullName)
        $destination=Join-Path "$target/final-lint" $relative
        New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force|Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $destination
        [ordered]@{path=$relative;errors=@($xml.issues.issue|Where-Object {$_.severity -in @('Error','Fatal')}).Count;
            warnings=@($xml.issues.issue|Where-Object {$_.severity -eq 'Warning'}).Count}
    })
    $lint|ConvertTo-Json -Depth 5|Set-Content "$target/lint-summary.json" -Encoding utf8
    if(@($lint|Where-Object {$_.errors -gt 0}).Count -gt 0){throw 'Lint reports contain errors'}
    $runs=@(Get-ChildItem "$target/builds/*.json"|Sort-Object Name|ForEach-Object {
        $data=Get-Content -LiteralPath $_.FullName -Raw|ConvertFrom-Json
        $log=Join-Path $_.DirectoryName ($_.BaseName+'.log')
        if($data.logSha256 -and (Get-FileHash -LiteralPath $log).Hash -ne $data.logSha256){throw 'Build log hash mismatch'}
        [pscustomobject]@{runId=$data.runId;command=$data.command;exitCode=$data.exitCode;commit=$data.commit;logSha256=$data.logSha256}
    })
    $runs|Export-Csv docs/stage-2/构建命令与结果.tsv -Delimiter "`t" -NoTypeInformation -Encoding utf8
    & git -c "safe.directory=$root" -c core.quotepath=false diff --cached --name-status 350a0167d4dfa4edcf5e86b6149627e206cbcee6 |Set-Content docs/stage-2/代码修改清单.tsv -Encoding utf8
    & git -c "safe.directory=$root" log --reverse --format='%H%x09%s' '350a0167..HEAD' |Set-Content docs/stage-2/代码提交清单.tsv -Encoding utf8
    $apk='app/app/build/outputs/apk/debug/school-helper-debug-v0.2.0.apk'
    $aar='app/feature/timetable/build/outputs/aar/timetable-debug.aar'
    [ordered]@{apk=(Resolve-Path $apk).Path;apkSha256=(Get-FileHash $apk).Hash;apkBytes=(Get-Item $apk).Length;
        aar=(Resolve-Path $aar).Path;aarSha256=(Get-FileHash $aar).Hash;unitTests=$total;
        lintReports=$lint.Count;unitFailures=0;lintErrors=0}|ConvertTo-Json|Set-Content "$target/artifacts.json" -Encoding utf8
    "Exported $total successful unit results and $($lint.Count) error-free Lint reports."
} finally {Pop-Location}
