param([Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9_-]{1,60}$')][string]$RunId)
$ErrorActionPreference='Stop'
$repo=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace=Split-Path (Split-Path $repo -Parent) -Parent
$directory=Join-Path $workspace '.private/stage-3/analysis/excel-producer'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$output=Join-Path $directory ($RunId+'.xlsx')
if(Test-Path -LiteralPath $output){throw 'Unique RunId required'}
$excel=$null; $book=$null; $sheet=$null; $owned=$false; $version=$null; $step='activate'
function Set-NumericCell([object]$targetSheet,[int]$row,[int]$column,[double]$number) {
    $cell=$targetSheet.Cells.Item($row,$column)
    try {
        [void]$cell.GetType().InvokeMember('Value2',[Reflection.BindingFlags]::SetProperty,$null,$cell,[object[]]@($number),[cultureinfo]::GetCultureInfo('en-US'))
    } finally {
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($cell)
    }
}
try {
    $excel=New-Object -ComObject Excel.Application
    $step='check isolated instance'
    if($excel.Workbooks.Count -ne 0){throw 'Refusing an application with existing workbooks'}
    $owned=$true
    $step='configure new instance'
    $excel.Visible=$false
    $excel.DisplayAlerts=$false
    $excel.AutomationSecurity=3
    $excel.IgnoreRemoteRequests=$true
    $version=[string]$excel.Version
    $step='create empty workbook'
    $book=$excel.Workbooks.Add(-4167)
    $sheet=$book.Worksheets.Item(1)
    $sheet.Name='Synthetic courses'
    $sheet.Cells.Font.Name='Arial'
    $step='populate synthetic cells'
    $headers=@('courseName','dayOfWeek','weeks','sections','date','startTime','endTime','teacher','room')
    for($i=0;$i -lt $headers.Count;$i++){$sheet.Cells.Item(1,$i+1).Value2=$headers[$i]}
    $sheet.Cells.Item(2,1).Value2='Synthetic mathematics'
    Set-NumericCell $sheet 2 2 1
    $sheet.Cells.Item(2,3).NumberFormat='@'
    $sheet.Cells.Item(2,3).Value2='1,3,5,8'
    $sheet.Cells.Item(2,4).NumberFormat='@'
    $sheet.Cells.Item(2,4).Value2='1,3'
    Set-NumericCell $sheet 2 6 (8.0/24.0)
    Set-NumericCell $sheet 2 7 (9.0/24.0)
    $sheet.Range('F2:G3').NumberFormat='hh:mm'
    $sheet.Cells.Item(2,8).Value2='Synthetic teacher A'
    $sheet.Cells.Item(2,9).Value2='Synthetic room A'
    $sheet.Cells.Item(3,1).Value2='Synthetic laboratory'
    Set-NumericCell $sheet 3 5 ([datetime]'2027-01-01').ToOADate()
    $sheet.Cells.Item(3,5).NumberFormat='yyyy-mm-dd'
    Set-NumericCell $sheet 3 6 (13.0/24.0)
    Set-NumericCell $sheet 3 7 (14.5/24.0)
    $sheet.Cells.Item(3,8).Value2='Synthetic teacher B'
    $sheet.Cells.Item(3,9).Value2='Synthetic room B'
    $sheet.Range('A1:I1').Font.Bold=$true
    $step='save synthetic workbook'
    $book.SaveAs($output,51)
    $book.Close($false)
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($book)
    $book=$null
} catch {
    $_ | Out-String | Set-Content -LiteralPath (Join-Path $directory ($RunId+'-error.txt')) -Encoding utf8
    throw "Synthetic Excel generation failed at $step (HResult $($_.Exception.HResult)); no existing document was opened"
} finally {
    if($book -and $owned){$book.Close($false)}
    if($owned -and $excel -and $excel.Workbooks.Count -eq 0){$excel.Quit()}
    foreach($item in @($sheet,$book,$excel)){
        if($null -ne $item -and [Runtime.InteropServices.Marshal]::IsComObject($item)){
            [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($item)
        }
    }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
[ordered]@{runId=$RunId;created=$true;producer='Microsoft Excel';producerVersion=$version;syntheticRows=2;sha256=(Get-FileHash -LiteralPath $output).Hash;privateUntilMetadataSanitized=$true} | ConvertTo-Json
