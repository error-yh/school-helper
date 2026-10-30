/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing

import java.io.File
import java.util.Base64
import com.lingion.sleepy.domain.imports.*
import com.lingion.sleepy.domain.model.defaultPeriods
import com.lingion.sleepy.importing.formats.FormatParseResult
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in private glyph contract check. Samples and detailed outputs never enter test reports. */
class PrivatePdfLayoutTest {
    @Test fun `authorized private coordinate extracts are parsed without printing personal data`() {
        val root=System.getenv("STAGE3_COORDINATES_DIR")
        assumeTrue("Private sample verification is opt-in",root!=null)
        for(sample in listOf("a","b","c")) {
            val rows=File(root,"pdf-$sample.tsv").readLines()
            val pages=rows.filter { it.startsWith("P\t") }.map { line ->
                val p=line.split('\t'); val number=p[1].toInt()
                val glyphs=rows.filter { it.startsWith("T\t$number\t") }.map {
                    val s=it.split('\t');PositionedText(String(Base64.getDecoder().decode(s[6]),Charsets.UTF_8),SourcePosition(number,TextBox(s[2].toFloat(),s[3].toFloat(),s[4].toFloat(),s[5].toFloat())))
                }
                PositionedPage(number,p[2].toFloat(),p[3].toFloat(),glyphs)
            }
            val result=PdfTimetableParser().parse(PositionedDocument(pages))
            assertTrue("sample-$sample contains no recognized draft",result.courses.isNotEmpty())
            val meetings=result.courses.flatMap { it.meetings }
            File(root,"pdf-$sample-debug-private.txt").writeText(result.toString())
            val session=ImportReviewSession.create(FrozenImportSource.fromFormat(FormatParseResult(result)),
                ImportReviewContext(ImportTarget("private-local",1,0,4),ImportMode.APPEND,LocalDate.of(2026,9,7),targetPeriods=defaultPeriods()))
            val requirements=session.requirements
            val decisions=ImportReviewDecisions(
                evidence=requirements.filter {it.kind==ReviewRequirementKind.EVIDENCE}.map {session.reviewEvidence(it.fingerprint)},
                times=requirements.filter {it.kind==ReviewRequirementKind.TIME}.map {session.reviewTime(it.source,if(result.periodProposals.isEmpty()) TimeResolutionChoice.USE_TARGET_PERIODS else TimeResolutionChoice.USE_SOURCE_PERIODS)},
                unscheduled=requirements.filter {it.kind==ReviewRequirementKind.KEEP_UNSCHEDULED}.map {session.keepUnscheduled(it.source)},
                optionalMetadata=requirements.filter {it.kind==ReviewRequirementKind.RETAIN_UNKNOWN_OPTIONAL_METADATA}.map {session.retainUnknownOptionalMetadata(it.source,session.unknownOptionalFields(it.source))})
            val reviewed=RichDraftConverter.convert(session,decisions)
            val codes=(reviewed as? ImportReviewResult.Blocked)?.problems?.map {it.code}.orEmpty()
            assertTrue("sample-$sample real PDF review failed: $codes",reviewed is ImportReviewResult.Ready)
            if(sample in setOf("b","c")) {
                val normalized=(reviewed as ImportReviewResult.Ready).reviewed
                assertEquals("sample-$sample original source count retained",if(sample=="b") 23 else 28,normalized.counts.selectedSourceMeetings)
                assertEquals("sample-$sample same-lesson groups require explicit review",3,requirements.count {it.code in setOf("SAME_LESSON_MULTIPLE_ROOMS_REVIEW_REQUIRED","SAME_LESSON_SOURCE_FRAGMENTS_REVIEW_REQUIRED")})
                assertEquals("sample-$sample exact normalized lesson count",if(sample=="b") 20 else 25,normalized.courses.sumOf {it.meetings.size})
                assertEquals("sample-$sample every original source remains mapped",if(sample=="b") 23 else 28,normalized.sourceMappings.size)
                val target=com.lingion.sleepy.domain.model.TimetableSnapshot(
                    settings=com.lingion.sleepy.domain.model.SemesterSettings(startDate=LocalDate.of(2026,9,7)),
                    periods=defaultPeriods(),scopeToken="private-local")
                val plan=ImportOccurrencePlanner.plan(normalized,LoadedImportTarget(target,4))
                assertTrue("sample-$sample reviewed same-lesson parts must not create false conflicts",plan.ready && plan.conflicts.isEmpty())
            }
            val expected=mapOf("a" to Triple(12,22,4),"b" to Triple(9,23,0),"c" to Triple(14,28,1)).getValue(sample)
            assertEquals("sample-$sample profile count",expected.first,result.courses.size)
            assertEquals("sample-$sample arrangement count",expected.second,meetings.size)
            assertEquals("sample-$sample unscheduled count",expected.third,meetings.count { it.kind==MeetingKind.UNSCHEDULED })
            if(sample!="c") {
                val expectedDays=if(sample=="a") listOf(4,3,2,2,2,2,3) else listOf(6,2,3,8,3,1,0)
                assertEquals("sample-$sample independent weekday distribution",expectedDays,(1..7).map { day -> meetings.count { it.kind==MeetingKind.RECURRING && it.dayOfWeek==day } })
                if(sample=="b") assertEquals("sample-b independent archive meeting multiplicities",listOf(1,1,1,2,2,2,3,5,6),result.courses.map { it.meetings.size }.sorted())
                fun hash(value: String?): String = java.security.MessageDigest.getInstance("SHA-256").digest(PdfTimetableParser.normalize(value.orEmpty()).replace(Regex("\\s+"),"").toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
                val expectedRecords=File(root,"pdf-$sample-full-reference.tsv").readLines().filter { it.isNotBlank() }.sorted()
                val actualRecords=result.courses.flatMap { c -> c.meetings.filter { it.kind==MeetingKind.RECURRING }.map { m ->
                    listOf(m.dayOfWeek.toString(),m.sections.sorted().joinToString(","),m.weeks.sorted().joinToString(","),hash(m.teacher),hash(m.room),hash(c.name),hash(c.sourceClassCode),hash(c.sourceCourseCode)).joinToString("\t")
                } }.sorted()
                assertTrue("sample-$sample independent source week/section/teacher/room multiset",expectedRecords==actualRecords)
                val sourceText=pages.flatMap { it.text }.joinToString(""){it.text}
                val anchorCount=Regex("\\(\\d{1,2}-\\d{1,2}节\\)").findAll(sourceText).count()
                assertEquals("sample-$sample independently extracted source section anchors",expected.second-expected.third,anchorCount)
                assertEquals("sample-$sample retained recurring source anchors",anchorCount,meetings.count { it.kind==MeetingKind.RECURRING })
            }
            if(sample=="c") {
                fun hash(value: String?): String = java.security.MessageDigest.getInstance("SHA-256").digest(PdfTimetableParser.normalize(value.orEmpty()).replace(Regex("\\s+"),"").toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
                val expectedRecords=File(root,"pdf-c-full-reference.tsv").readLines().filter { it.isNotBlank() }.sorted()
                fun time(t: java.time.LocalTime?) = t?.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")).orEmpty()
                val actualRecords=result.courses.flatMap { c -> c.meetings.filter { it.kind==MeetingKind.RECURRING }.map { m -> listOf(m.dayOfWeek.toString(),m.sections.sorted().joinToString(","),m.weeks.sorted().joinToString(","),time(m.customTime?.start),time(m.customTime?.end),hash(m.teacher),hash(m.room),hash(c.name),hash(c.sourceClassCode),hash(c.sourceCourseCode)).joinToString("\t") } }.sorted()
                assertTrue("sample-c independent precise week/section/time/teacher/room multiset",expectedRecords==actualRecords)
                assertEquals("sample-c independent weekday distribution",listOf(3,3,5,10,5,1,0),(1..7).map { day -> meetings.count { it.kind==MeetingKind.RECURRING && it.dayOfWeek==day } })
            }
            if(sample!="b") {
                fun hash(value: String?): String = java.security.MessageDigest.getInstance("SHA-256").digest(PdfTimetableParser.normalize(value.orEmpty()).replace(Regex("\\s+"),"").toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
                val expectedUnscheduled=File(root,"pdf-$sample-unscheduled-reference.tsv").readLines().filter { it.isNotBlank() }.sorted()
                val actualUnscheduled=result.courses.flatMap { c -> c.meetings.filter { it.kind==MeetingKind.UNSCHEDULED }.map { m ->
                    assertTrue("sample-$sample no invented unscheduled day/time/section",m.dayOfWeek==null && m.sections.isEmpty() && m.customTime==null && m.dates.isEmpty())
                    listOf(hash(c.name),hash(c.sourceClassCode),hash(c.sourceCourseCode),m.weeks.sorted().joinToString(","),hash(m.teacher),hash(m.room)).joinToString("\t")
                } }.sorted()
                assertTrue("sample-$sample independently extracted unscheduled source multiset",expectedUnscheduled==actualUnscheduled)
            }
            val report=buildString {
                appendLine("courses=${result.courses.size} meetings=${meetings.size} unscheduled=${meetings.count { it.kind==MeetingKind.UNSCHEDULED }} warnings=${meetings.sumOf { it.warnings.size }} unparsed=${result.unparsed.size}")
                result.courses.forEach { c -> appendLine("${c.name}\t${c.sourceCourseCode}\t${c.sourceClassCode}"); c.meetings.forEach { m -> appendLine("${m.kind}\t${m.dayOfWeek}\t${m.weeks}\t${m.sections}\t${m.customTime}\t${m.teacher}\t${m.room}\t${m.missingFields}\t${m.warnings.map { it.code }}") } }
                result.unparsed.forEach { appendLine("UNPARSED ${it.rawText}") }
            }
            File(root,"pdf-$sample-parsed-private.txt").writeText(report)
            println("sample-$sample pages=${pages.size} profiles=${result.courses.size} arrangements=${meetings.size} unscheduled=${meetings.count { it.kind==MeetingKind.UNSCHEDULED }} missing=${meetings.count { it.missingFields.isNotEmpty() }} warnings=${meetings.sumOf { it.warnings.size }} unparsed=${result.unparsed.size}")
        }
    }
}





