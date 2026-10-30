param([string]$RunId='isolation')
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$build=Get-Content (Join-Path $root 'app/app/build.gradle.kts') -Raw
$manifest=Get-Content (Join-Path $root 'app/app/src/main/AndroidManifest.xml') -Raw
$production=Get-ChildItem (Join-Path $root 'app/app/src/main') -Recurse -File |
    Where-Object {$_.Extension -in @('.kt','.xml','.json')}
$oldSourceHits=@($production | Select-String -Pattern 'msq\.(l2|12)\.ink|cdn\.jsdelivr\.net/gh/njpemo/mashangqu|raw\.githubusercontent\.com/njpemo/mashangqu')
$checks=@(
    [ordered]@{name='Independent applicationId';passed=($build -match 'applicationId\s*=\s*"io\.github\.schoolhelper\.app"')},
    [ordered]@{name='No executable original update sources';passed=($oldSourceHits.Count -eq 0)},
    [ordered]@{name='No release debug signing fallback';passed=($build -notmatch 'signingConfig\s*=\s*signingConfigs\.getByName\("debug"\)')},
    [ordered]@{name='Release signing gate';passed=($build -match 'verifyReleaseSigning')},
    [ordered]@{name='No unused install permission';passed=($manifest -notmatch 'REQUEST_INSTALL_PACKAGES')},
    [ordered]@{name='Provider bound to independent app identity';passed=($manifest.Contains('${applicationId}.fileprovider'))}
)
$evidence=Join-Path $root 'docs/stage-1/evidence'
New-Item -ItemType Directory $evidence -Force|Out-Null
$checks|ConvertTo-Json -Depth 4|Set-Content (Join-Path $evidence ($RunId+'.json')) -Encoding utf8
$failed=@($checks|Where-Object {-not $_.passed})
Write-Output ('Isolation checks: '+($checks.Count-$failed.Count)+'/'+$checks.Count)
if($failed.Count){$failed|ForEach-Object {Write-Output ('FAIL: '+$_.name)};exit 1}
