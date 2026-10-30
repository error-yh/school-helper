/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingion.sleepy.importing.formats.FormatParseContext
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Invented small-print table: exercises native OCR and actual geometry adapters together. */
@RunWith(AndroidJUnit4::class)
class TesseractTableLayoutTest {
    @Test fun smallPrintDateTableRetainsActualDatedRows() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val bitmap=Bitmap.createBitmap(976,320,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=16f}
            val xs=listOf(0f,120f,240f,360f,480f,600f,720f,840f,975f)
            val ys=listOf(0f,48f,138f,228f,319f)
            paint.strokeWidth=1f
            xs.forEach {canvas.drawLine(it,0f,it,319f,paint)}
            ys.forEach {canvas.drawLine(0f,it,975f,it,paint)}
            val header=listOf("周次","星期","时间","节次/时间","班级名称","课程名称","项目名称","实验室")
            header.forEachIndexed {i,t->canvas.drawText(t,xs[i]+8f,28f,paint)}
            val dates=listOf("20261015","20261021","20261023")
            dates.forEachIndexed {row,date->
                val values=listOf(if(row==0) "第6周" else "第7周",listOf("星期4","星期3","星期5")[row],date,
                    "1-4(08:00-11:50)","测试班级","测试课程","项目甲","实验室甲")
                values.forEachIndexed {col,text->
                    // Keep the combined period/time legible inside its literal cell.
                    if(col==3) {canvas.drawText("1-4",xs[col]+8f,ys[row+1]+28f,paint);canvas.drawText("(08:00-11:50)",xs[col]+8f,ys[row+1]+50f,paint)}
                    else canvas.drawText(text,xs[col]+8f,ys[row+1]+28f,paint)
                }
            }
            val resources=RasterResources(OcrWorkBudget())
            val page=OcrRasterPage(1,bitmap,976f,320f,PageAffineTransform.IDENTITY)
            val lines=TableLineDetector().detect(page,resources)
            val native=TesseractLocalOcrEngine(context).recognize(page,resources.recognitionOptions())
            assertNull("Native failure code only: ${native.failure}",native.failure)
            val parsed=OcrTimetableParser().parse(OcrLayoutDocument(listOf(OcrLayoutPage(1,976f,320f,
                native.variants,native.recommendedVariantId,lines.lines))),
                OcrLayoutContext(FormatParseContext("synthetic-small-table",20),confirmedTermMonday=LocalDate.of(2026,9,7)))
            val meetings=parsed.formatResult.draft?.courses.orEmpty().flatMap {it.meetings}
            assertEquals("Synthetic table must yield each actual dated row; issues=${parsed.formatResult.issues.map {it.code}}",3,meetings.size)
            assertEquals(dates.map {LocalDate.parse(it,java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}.toSet(),meetings.flatMap {it.dates}.toSet())
        } finally {bitmap.recycle()}
    }
}
