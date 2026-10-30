param([Parameter(Mandatory)][string]$InputPath)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repo=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$outputDirectory=Join-Path $repo 'docs/stage-3/import-examples/xlsx'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$output=Join-Path $outputDirectory 'excel-produced-synthetic.xlsx'
if(Test-Path -LiteralPath $output){throw 'Output already exists'}
$allowed=@('[Content_Types].xml','_rels/.rels','xl/workbook.xml','xl/_rels/workbook.xml.rels','xl/worksheets/sheet1.xml','xl/theme/theme1.xml','xl/styles.xml','xl/sharedStrings.xml','docProps/core.xml','docProps/app.xml')
$privateValues=[Collections.Generic.List[string]]::new()
$parts=[Collections.Generic.Dictionary[string,string]]::new()
$inputZip=[IO.Compression.ZipFile]::OpenRead($InputPath)
try {
 foreach($entry in $inputZip.Entries){
  if($entry.FullName -notin $allowed){throw 'Unexpected workbook part'}
  $settings=[Xml.XmlReaderSettings]::new();$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null
  $stream=$entry.Open();$reader=[Xml.XmlReader]::Create($stream,$settings)
  $doc=[Xml.XmlDocument]::new();$doc.XmlResolver=$null
  try {$doc.Load($reader)} finally {$reader.Dispose();$stream.Dispose()}
  if($entry.FullName -eq 'docProps/core.xml'){
   foreach($node in $doc.DocumentElement.ChildNodes){if($node.InnerText){$privateValues.Add($node.InnerText)}}
   $doc.LoadXml('<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:creator>Synthetic fixture</dc:creator><cp:lastModifiedBy>Synthetic fixture</cp:lastModifiedBy></cp:coreProperties>')
  }
  if($entry.FullName -eq 'docProps/app.xml'){
   foreach($node in $doc.SelectNodes('//*[local-name()="Company" or local-name()="Manager"]')){if($node.InnerText){$privateValues.Add($node.InnerText)};$node.InnerText=''}
  }
  foreach($element in $doc.SelectNodes('//*')){
   if($element.LocalName -eq 'f' -and $element.NamespaceURI -eq 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'){throw 'Unexpected formula'}
   if($element.LocalName -eq 'Relationship' -and $element.GetAttribute('TargetMode') -eq 'External'){throw 'Unexpected external relationship'}
   foreach($attr in @($element.Attributes)){
    if($element.LocalName -eq 'absPath' -and $attr.LocalName -eq 'url'){$privateValues.Add($attr.Value);$attr.Value='C:\SyntheticFixture\'}
    elseif($attr.LocalName -in @('uid','uidLastSave','documentId')){$privateValues.Add($attr.Value);$attr.Value='{00000000-0000-4000-8000-000000000001}'}
   }
  }
  $parts.Add($entry.FullName,$doc.OuterXml)
 }
} finally {$inputZip.Dispose()}
foreach($value in $privateValues){if($value.Length -gt 3){foreach($text in $parts.Values){if($text.Contains($value)){throw 'Metadata scrub verification failed'}}}}
$zip=[IO.Compression.ZipFile]::Open($output,[IO.Compression.ZipArchiveMode]::Create)
try {foreach($name in $parts.Keys){$entry=$zip.CreateEntry($name);$entry.LastWriteTime=[DateTimeOffset]::new(2026,1,1,0,0,0,[TimeSpan]::Zero);$writer=[IO.StreamWriter]::new($entry.Open(),[Text.UTF8Encoding]::new($false));try{$writer.Write($parts[$name])}finally{$writer.Dispose()}}} finally {$zip.Dispose()}
[ordered]@{syntheticOnly=$true;parts=$parts.Count;metadataScrubbed=$true;formulaCount=0;externalRelationships=0;sha256=(Get-FileHash -LiteralPath $output).Hash}|ConvertTo-Json
