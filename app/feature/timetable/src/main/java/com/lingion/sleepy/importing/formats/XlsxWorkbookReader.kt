/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import com.lingion.sleepy.importing.ParseIssue
import java.io.InputStream
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import org.xml.sax.Attributes

/** Literal, read-only OOXML envelope. Caller chooses sheet/mapping, dispatcher and stream lifetime. */
class XlsxWorkbookReader {
    fun read(input: InputStream, context: FormatParseContext): WorkbookReadResult = try {
        Reader(SafeXlsxPackage.read(input,context),context).read()
    } catch (failure: ImportRejected) { WorkbookReadResult(null,listOf(publicIssue(failure.code))) }

    private class Reader(val pkg: SafeXlsxPackage, val context: FormatParseContext) {
        private val limits=context.limits
        private var retained=0
        private var cells=0
        private var merges=0
        private var mergeComparisons=0L
        private val allIssues=mutableListOf<ParseIssue>()
        private fun compareMerge() {
            context.cancellation.check()
            if(++mergeComparisons>limits.maxMergeComparisons.toLong()) reject("LIMIT_EXCEEDED")
        }
        private fun retain(text:String):String {
            context.cancellation.check()
            if (text.length>limits.maxFieldUnits || text.length>limits.maxTextUnits-retained) reject("LIMIT_EXCEEDED")
            retained+=text.length; return text
        }
        private data class Sheet(val name:String,val id:String,val visibility:SheetVisibility)
        fun read():WorkbookReadResult {
            val workbook=pkg.workbookPart()
            var date1904=false; var properties=false; var sheetList=false
            val sheets=mutableListOf<Sheet>(); val sheetIds=mutableSetOf<String>(); val names=mutableSetOf<String>(); val ids=mutableSetOf<String>()
            pkg.xml(workbook,XLSX_MAIN,start={ path, attrs ->
                when(path) {
                    listOf("workbook") -> Unit
                    listOf("workbook","workbookPr") -> {
                        if(properties) reject(); properties=true
                        date1904=when(attrs.getValue("date1904")) { null,"0","false" -> false; "1","true" -> true; else -> reject() }
                    }
                    listOf("workbook","sheets") -> { if(sheetList) reject(); sheetList=true }
                    listOf("workbook","sheets","sheet") -> {
                        if(sheets.size>=limits.maxSheets) reject("LIMIT_EXCEEDED")
                        val name=retain(attrs.required("name")); val id=attrs.getValue(XLSX_REL,"id") ?: reject()
                        if(!ids.add(id) || !names.add(name) || !sheetIds.add(attrs.required("sheetId"))) reject()
                        val visibility=when(attrs.getValue("state")) { null,"visible" -> SheetVisibility.VISIBLE; "hidden" -> SheetVisibility.HIDDEN; "veryHidden" -> SheetVisibility.VERY_HIDDEN; else -> reject() }
                        sheets+=Sheet(name,id,visibility)
                    }
                    else -> if(path.size==1 || path.last() in setOf("sheet","workbookPr","sheets")) reject()
                }
            })
            if(sheets.isEmpty()) reject()
            val relations=pkg.relationships(workbook)
            fun singleton(type:String):String? {
                val matches=relations.filter { it.type=="$XLSX_REL/$type" }
                if(matches.size>1) reject(); return matches.singleOrNull()?.target
            }
            val shared=singleton("sharedStrings")?.let { part ->
                if(pkg.contentType(part)!="${XLSX_CT}sharedStrings+xml") {
                    // A Default .xml content type is permitted by OPC, but must still be exact.
                    // No filename-based fallback is used.
                    reject()
                }
                strings(part)
            }.orEmpty()
            val styles=singleton("styles")?.let { part ->
                if(pkg.contentType(part)!="${XLSX_CT}styles+xml") reject()
                styles(part)
            }.orEmpty()
            val result=sheets.mapIndexed { index,sheet ->
                val relation=relations.singleOrNull { it.id==sheet.id } ?: reject()
                if(relation.type=="$XLSX_REL/worksheet") {
                    if(pkg.contentType(relation.target)!="${XLSX_CT}worksheet+xml") reject()
                    worksheet(relation.target,index+1,sheet,shared,styles,date1904)
                } else {
                    if(relation.type in setOf("$XLSX_REL/styles","$XLSX_REL/sharedStrings","$XLSX_REL/officeDocument")) reject()
                    // Preserve unknown safe internal sheet declarations without following their
                    // content or guessing a worksheet. Forbidden parts were rejected by OPC.
                    val issues=listOf(publicIssue("UNSUPPORTED_FORMAT")); allIssues+=issues
                    WorksheetData(index+1,sheet.name,sheet.id,sheet.visibility,SheetKind.UNSUPPORTED,null,issues=issues)
                }
            }
            if(pkg.passiveMetadataIgnored) allIssues+=publicIssue("XLSX_PASSIVE_METADATA_IGNORED")
            return WorkbookReadResult(WorkbookData(context.sourceId,date1904,result),allIssues.toList())
        }

        private fun strings(part:String):List<String> {
            val strings=mutableListOf<String>(); var value=StringBuilder(); var inside=false
            pkg.xml(part,XLSX_MAIN,start={ path,_ ->
                if(path.size==1 && path[0]!="sst") reject()
                if(path.last()=="si") { if(path!=listOf("sst","si") || inside) reject(); inside=true; value=StringBuilder() }
            },text={ path,text ->
                if(path.lastOrNull()=="t" && path[0]=="sst" && "si" in path && "rPh" !in path && "phoneticPr" !in path) {
                    if(value.length+text.length>limits.maxFieldUnits) reject("LIMIT_EXCEEDED"); value.append(text)
                }
            },end={ path ->
                if(path==listOf("sst","si")) {
                    if(strings.size>=limits.maxCells) reject("LIMIT_EXCEEDED")
                    strings+=retain(value.toString()); inside=false
                }
            })
            return strings.toList()
        }

        private fun styles(part:String):List<CellStyle> {
            val formats=mutableMapOf<Int,String>(); val styles=mutableListOf<CellStyle>(); var xfs=false
            pkg.xml(part,XLSX_MAIN,start={ path,attrs ->
                when(path) {
                    listOf("styleSheet") -> Unit
                    listOf("styleSheet","numFmts","numFmt") -> {
                        val id=positiveOrZero(attrs.required("numFmtId"))
                        if(id<164 || formats.put(id,retain(attrs.required("formatCode")))!=null) reject()
                        if(formats.size>limits.maxCells) reject("LIMIT_EXCEEDED")
                    }
                    listOf("styleSheet","cellXfs") -> { if(xfs) reject(); xfs=true }
                    listOf("styleSheet","cellXfs","xf") -> {
                        if(styles.size>=limits.maxCells) reject("LIMIT_EXCEEDED")
                        val id=positiveOrZero(attrs.getValue("numFmtId") ?: "0")
                        styles+=CellStyle(styles.size,id,null,NumberFormatKind.GENERAL)
                    }
                    else -> if(path.size==1) reject()
                }
            })
            return styles.map { style -> val code=formats[style.numberFormatId]; style.copy(formatCode=code,kind=ExcelCellFormat.classify(style.numberFormatId,code)) }
        }

        private data class Cell(
            val column:Int,val locator:String,val type:String?,val styleIndex:String?,
            val raw:StringBuilder=StringBuilder(),val inline:StringBuilder=StringBuilder(),val formula:StringBuilder=StringBuilder(),
            var hasValue:Boolean=false,var hasInline:Boolean=false,var hasFormula:Boolean=false,
        )
        private fun worksheet(part:String,number:Int,sheet:Sheet,shared:List<String>,styles:List<CellStyle>,date1904:Boolean):WorksheetData {
            val rows=mutableListOf<ImportRow>(); val regions=mutableListOf<MergeRegion>(); val issues=mutableListOf<ParseIssue>()
            var row=0; var lastRow=0; var lastColumn=0; var rowCells=mutableListOf<ImportCell>(); var current:Cell?=null
            var data=false; var mergeContainer=false
            fun append(builder:StringBuilder,text:String) { if(text.length>limits.maxFieldUnits-builder.length) reject("LIMIT_EXCEEDED"); builder.append(text) }
            pkg.xml(part,XLSX_MAIN,start={ path,attrs ->
                when(path) {
                    listOf("worksheet") -> Unit
                    listOf("worksheet","sheetData") -> { if(data) reject(); data=true }
                    listOf("worksheet","sheetData","row") -> {
                        row=attrs.getValue("r")?.let(::positive) ?: (lastRow+1)
                        if(row>limits.maxRows) reject("LIMIT_EXCEEDED")
                        if(row<=lastRow) reject()
                        rowCells=mutableListOf(); lastColumn=0
                    }
                    listOf("worksheet","sheetData","row","c") -> {
                        if(++cells>limits.maxCells) reject("LIMIT_EXCEEDED")
                        val reference=attrs.getValue("r")
                        val position=reference?.let(::coordinate) ?: (row to lastColumn+1)
                        if(position.first!=row || position.second<=lastColumn) reject()
                        if(position.second>limits.maxColumns) reject("LIMIT_EXCEEDED")
                        lastColumn=position.second
                        current=Cell(lastColumn,"sheet:$number!${cellName(row,lastColumn)}",attrs.getValue("t"),attrs.getValue("s"))
                    }
                    listOf("worksheet","sheetData","row","c","v") -> { val c=current ?: reject(); if(c.hasValue) reject(); c.hasValue=true }
                    listOf("worksheet","sheetData","row","c","is") -> { val c=current ?: reject(); if(c.hasInline) reject(); c.hasInline=true }
                    listOf("worksheet","sheetData","row","c","f") -> { val c=current ?: reject(); if(c.hasFormula) reject(); c.hasFormula=true }
                    listOf("worksheet","mergeCells") -> { if(mergeContainer) reject(); mergeContainer=true }
                    listOf("worksheet","mergeCells","mergeCell") -> {
                        if(++merges>limits.maxMerges) reject("LIMIT_EXCEEDED")
                        val ref=attrs.required("ref").split(':'); if(ref.size!=2) reject()
                        val first=coordinate(ref[0]); val last=coordinate(ref[1])
                        if(first.first>last.first || first.second>last.second || first==last) reject()
                        val region=MergeRegion(first.first,first.second,last.first,last.second)
                        for(existing in regions) {
                            compareMerge()
                            if(region.firstRow<=existing.lastRow && region.lastRow>=existing.firstRow && region.firstColumn<=existing.lastColumn && region.lastColumn>=existing.firstColumn) reject()
                        }
                        regions+=region
                    }
                    else -> if(path.size==1 || path.last() in setOf("row","c","v","f","is","sheetData","mergeCells","mergeCell")) reject()
                }
            },text={ path,text ->
                val cell=current
                if(cell!=null) when {
                    path==listOf("worksheet","sheetData","row","c","v") -> append(cell.raw,text)
                    path==listOf("worksheet","sheetData","row","c","f") -> append(cell.formula,text)
                    path.lastOrNull()=="t" && "is" in path && "rPh" !in path && "phoneticPr" !in path -> append(cell.inline,text)
                }
            },end={ path ->
                when(path) {
                    listOf("worksheet","sheetData","row","c") -> {
                        val c=current ?: reject(); val cell=convert(c,shared,styles,date1904)
                        rowCells+=cell; issues+=cell.issues; current=null
                    }
                    listOf("worksheet","sheetData","row") -> { rows+=ImportRow(row,rowCells.toList()); lastRow=row }
                }
            })
            if(!data) reject()
            val mergedRows=rows.map { r -> r.copy(cells=r.cells.map { cell ->
                context.cancellation.check()
                val region=regions.firstOrNull { compareMerge(); r.number in it.firstRow..it.lastRow && cell.column in it.firstColumn..it.lastColumn }
                if(region!=null && (r.number!=region.firstRow || cell.column!=region.firstColumn)) {
                    val coveredIssues=if(cell.rawText.isNotEmpty() || cell.formula!=null) listOf(publicIssue("AMBIGUOUS_CELL_SPAN")) else emptyList()
                    issues+=coveredIssues
                    cell.copy(mergeAnchor=cellName(region.firstRow,region.firstColumn),typedValue=null,issues=cell.issues+coveredIssues)
                } else cell
            }) }
            if(rows.all { r -> r.cells.all { it.typedValue==CellValue.Empty || it.typedValue==CellValue.Text("") } }) issues+=publicIssue("EMPTY_TABLE")
            allIssues+=issues
            return WorksheetData(number,sheet.name,sheet.id,sheet.visibility,SheetKind.WORKSHEET,ImportTable(context.sourceId,mergedRows),regions.toList(),issues.toList())
        }

        private fun convert(c:Cell,shared:List<String>,styles:List<CellStyle>,date1904:Boolean):ImportCell {
            val issues=mutableListOf<ParseIssue>()
            fun issue(code:String) { if(issues.none { it.code==code }) issues+=publicIssue(code) }
            val styleIndex=c.styleIndex?.toIntOrNull()
            val style=if(c.styleIndex==null) styles.firstOrNull() else styleIndex?.let { styles.getOrNull(it) }
            if(c.styleIndex!=null && style==null) issue("INVALID_CELL_VALUE")
            var raw=c.raw.toString(); var typed:CellValue?=null
            val type=c.type ?: "n"
            if(c.hasInline && (type!="inlineStr" || c.hasValue)) issue("INVALID_CELL_VALUE")
            if(c.hasFormula) issue("FORMULA_UNSUPPORTED")
            if(c.hasFormula) {
                retain(raw); retain(c.formula.toString())
                return ImportCell(c.column,raw,c.locator,null,style,c.formula.toString(),issues=issues.toList())
            }
            if(!c.hasValue && !c.hasInline && type in setOf("s","b","d","inlineStr","str","n")) {
                return ImportCell(c.column,"",c.locator,if(issues.isEmpty()) CellValue.Empty else null,style,issues=issues.toList())
            }
            when(type) {
                "inlineStr" -> { raw=c.inline.toString(); if(!c.hasInline) issue("INVALID_CELL_VALUE") else typed=CellValue.Text(raw) }
                "s" -> {
                    val index=raw.toIntOrNull()
                    if(index==null || index<0 || !raw.matches(Regex("[0-9]+")) || index>=shared.size) issue("INVALID_CELL_VALUE")
                    else { raw=shared[index]; typed=CellValue.Text(raw) }
                }
                "str" -> typed=CellValue.Text(raw)
                "b" -> typed=when(raw) { "0","false" -> CellValue.BooleanValue(false); "1","true" -> CellValue.BooleanValue(true); else -> { issue("INVALID_CELL_VALUE"); null } }
                "n" -> {
                    if(!c.hasValue || raw.isEmpty()) typed=CellValue.Empty else {
                        val value=if(raw.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[Ee][+-]?[0-9]{1,4})?"))) raw.toBigDecimalOrNull() else null
                        if(value==null || value.precision()>limits.maxFieldUnits || kotlin.math.abs(value.scale().toLong())>10_000L) issue("INVALID_CELL_VALUE") else {
                            var temporal:ExcelTemporalValue?=null
                            if(style?.kind in setOf(NumberFormatKind.DATE,NumberFormatKind.TIME,NumberFormatKind.DATE_TIME)) {
                                try { temporal=ExcelCellFormat.temporal(value,style!!.kind,date1904) { issue("XLSX_TIME_SERIAL_NORMALIZED") } } catch(failure:ImportRejected) { issue(failure.code) }
                            } else if(style?.kind==NumberFormatKind.UNSUPPORTED) issue("UNSUPPORTED_NUMBER_FORMAT")
                            typed=CellValue.Number(value,temporal)
                        }
                    }
                }
                "d" -> {
                    try {
                        val temporal=when {
                            raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) -> ExcelTemporalValue(date=LocalDate.parse(raw))
                            raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]+)?)?")) -> {
                                val value=LocalDateTime.parse(raw)
                                if(value.second!=0 || value.nano!=0) reject("INVALID_EXCEL_DATE")
                                ExcelTemporalValue(value.toLocalDate(),value.toLocalTime())
                            }
                            else -> reject("INVALID_EXCEL_DATE")
                        }
                        typed=CellValue.Temporal(temporal)
                    } catch(_:DateTimeException) { issue("INVALID_EXCEL_DATE") } catch(failure:ImportRejected) { issue(failure.code) }
                }
                else -> issue("INVALID_CELL_VALUE")
            }
            retain(raw)
            // Invalid style/value never becomes an implicitly verified value for downstream use.
            if(issues.any { it.code=="INVALID_CELL_VALUE" }) typed=null
            return ImportCell(c.column,raw,c.locator,typed,style,issues=issues.toList())
        }
        private fun positiveOrZero(value:String):Int = value.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull() ?: reject()
        private fun positive(value:String):Int = positiveOrZero(value).takeIf { it>0 } ?: reject()
        private fun coordinate(ref:String):Pair<Int,Int> {
            if(!ref.matches(Regex("[A-Z]{1,3}[1-9][0-9]{0,5}"))) reject()
            val letters=ref.takeWhile { it in 'A'..'Z' }; var column=0
            for(letter in letters) column=column*26+(letter-'A'+1)
            val row=positive(ref.substring(letters.length))
            if(row>limits.maxRows || column>limits.maxColumns) reject("LIMIT_EXCEEDED")
            return row to column
        }
        private fun cellName(row:Int,column:Int):String {
            var value=column; val name=StringBuilder()
            while(value>0) { value--; name.append(('A'.code+value%26).toChar()); value/=26 }
            return name.reverse().toString()+row
        }
    }
}
