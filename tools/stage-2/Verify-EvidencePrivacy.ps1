$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace=Split-Path (Split-Path $root -Parent) -Parent
$adb=Join-Path $workspace '.tools/android-sdk/platform-tools/adb.exe'
$env:ANDROID_SDK_HOME=Join-Path $workspace '.private'
$identifiers=@(& $adb devices|Where-Object {$_ -match '^\S+\s+device$'}|ForEach-Object {($_ -split '\s+')[0]})
$prefs=Get-Content -LiteralPath (Join-Path $workspace '.private/stage-2-backup/50-final-data-preservation/shared_prefs/timetable_local_profile.xml') -Raw
$profile=[regex]::Match($prefs,'<string name="id">([^<]+)</string>')
if(-not $profile.Success){throw 'Private identifier unavailable for privacy audit'}
$evidence=Join-Path $root 'docs/stage-2/evidence'
$files=@(Get-ChildItem $evidence -Recurse -File|Where-Object {$_.Extension -in @('.json','.xml','.log','.txt','.tsv')})
$deviceFound=$false;$profileFound=$false;$keyFound=$false
foreach($item in $files){
    $body=[string](Get-Content -LiteralPath $item.FullName -Raw)
    foreach($identifier in $identifiers){if($body.Contains($identifier)){$deviceFound=$true}}
    if($body.Contains($profile.Groups[1].Value)){$profileFound=$true}
    if($body -match '-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----'){$keyFound=$true}
}
$hashChecks=0
foreach($entryFile in Get-ChildItem (Join-Path $evidence 'device') -Filter *.json){
    $metadataDoc=ConvertFrom-Json -InputObject (Get-Content -LiteralPath $entryFile.FullName -Raw) -NoEnumerate
    if($metadataDoc -is [array]){continue}
    if(-not $metadataDoc.PSObject.Properties['logSha256']){continue}
    $log=Join-Path $entryFile.DirectoryName ($entryFile.BaseName+'.log')
    if((Get-FileHash -LiteralPath $log).Hash -ne $metadataDoc.logSha256){throw 'Device log hash mismatch'}
    $hashChecks++
}
$passed=-not($deviceFound -or $profileFound -or $keyFound)
[ordered]@{textFilesScanned=$files.Count;deviceIdentifierFound=$deviceFound;profileIdentifierFound=$profileFound;
    privateKeyFound=$keyFound;deviceLogHashesVerified=$hashChecks;passed=$passed}|ConvertTo-Json|
    Set-Content (Join-Path $evidence 'privacy-and-integrity.json') -Encoding utf8
if(-not $passed){throw 'Evidence privacy audit failed'}
'Evidence privacy and device log integrity passed.'
