/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import com.lingion.sleepy.importing.*
import java.time.LocalDate
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class NoticeTimetableParserTest {
    private val context=NoticeParseContext(FormatParseContext("invented-notice",30),LocalDate.of(2026,12,30))
    private fun parse(text:String,c:NoticeParseContext=context)=NoticeTimetableParser().parse(text,c)
    @Test fun `reschedule roles keep old and new sections and times separate`() {
        val r=parse("原2026-11-13示例数学由第3-4节(10:00-11:40)调整为第5-6节(14:00-15:40)")
        val p=r.pendingExceptions.single()
        val roles=requireNotNull(p.roles)
        assertEquals(NoticeIntentKind.RESCHEDULE_TIME,roles.intent)
        assertEquals(setOf(3,4),roles.original.sections)
        assertEquals(setOf(5,6),roles.target.sections)
        assertEquals("10:00",roles.original.time!!.start.toString())
        assertEquals("11:40",roles.original.time!!.end.toString())
        assertEquals("14:00",roles.target.time!!.start.toString())
        // The legacy draft carries the target override only; sections are never merged into 3-6.
        assertEquals(setOf(5,6),p.exception.sections)
        assertEquals("14:00",p.exception.customTime!!.start.toString())
        assertEquals(LocalDate.of(2026,11,13),p.exception.originalDate)
        assertEquals(LocalDate.of(2026,11,13),p.exception.targetDate)
        assertTrue(roles.sameDayAdjustment)
        assertTrue(r.issues.none { it.code=="AMBIGUOUS_TIME_MAPPING" })
    }

    @Test fun `room keep and room change are explicit roles without rewriting the legacy draft`() {
        val keep=parse("本周五示例数学由第3-4节调整为第5-6节，教室不变").pendingExceptions.single()
        assertEquals(NoticeRoomRole.KEEP,keep.roles!!.room!!.role)
        assertNull(keep.exception.room)
        val change=parse("2026-11-13示例数学第3-4节教室改为B203").pendingExceptions.single()
        assertEquals(NoticeIntentKind.ROOM_ONLY,change.roles!!.intent)
        assertEquals(NoticeRoomRole.CHANGE,change.roles!!.room!!.role)
        assertEquals("B203",change.roles!!.room!!.to)
        assertEquals(setOf(3,4),change.roles!!.original.sections)
        assertEquals("B203",change.exception.room)
        assertEquals(LocalDate.of(2026,11,13),change.exception.originalDate)
    }

    @Test fun `old extra notice keeps its previous draft fields and only gains optional roles`() {
        val r=parse("今日示例数学10:00-11:30在示例室A加课")
        val p=r.pendingExceptions.single()
        assertEquals(NoticeIntentKind.EXTRA,p.roles!!.intent)
        assertEquals(RichExceptionKind.EXTRA,p.exception.kind)
        assertEquals(LocalDate.of(2026,12,30),p.exception.targetDate)
        assertEquals("10:00",p.exception.customTime!!.start.toString())
        assertEquals("示例室A",p.exception.room)
        assertEquals("示例数学",p.courseName)
        assertNull(p.exception.sections)
    }

    @Test fun `relative year transition is a review proposal and extra stays pending`() {
        val r=parse("明日示例数学10:00-11:30在示例室A加课")
        val p=r.relativeDates.single()
        assertEquals("明日",p.originalToken)
        assertEquals(context.referenceDate,p.basisDate)
        assertEquals(LocalDate.of(2026,12,31),p.candidate)
        assertTrue(p.requiresReview)
        assertEquals(RichExceptionKind.EXTRA,r.pendingExceptions.single().exception.kind)
        assertEquals(LocalDate.of(2026,12,31),r.pendingExceptions.single().exception.targetDate)
        assertEquals("示例数学",r.pendingExceptions.single().courseName)
        assertEquals("示例室A",r.pendingExceptions.single().exception.room)
        assertTrue(r.draft!!.requiresConfirmation)
    }
    @Test fun `reschedule preserves original target and explicit time`() {
        val r=parse("原2026-10-05示例数学调至2026-10-06 10:00-11:30")
        val e=r.pendingExceptions.single().exception
        assertEquals(RichExceptionKind.RESCHEDULE,e.kind)
        assertEquals(LocalDate.of(2026,10,5),e.originalDate)
        assertEquals(LocalDate.of(2026,10,6),e.targetDate)
        assertEquals("10:00",e.customTime!!.start.toString())
    }
    @Test fun `recurring cancel needs confirmed term anchor and still never attaches by title`() {
        val text="第3周周一示例数学停课"
        val a=parse(text)
        assertNull(a.pendingExceptions.single().exception.originalDate)
        val b=parse(text,context.copy(confirmedTermMonday=LocalDate.of(2026,9,7)))
        assertEquals(LocalDate.of(2026,9,21),b.pendingExceptions.single().exception.originalDate)
        assertTrue(b.relativeDates.single().requiresReview)
    }
    @Test fun `user notice keeps exact weeks sections room and uncertain relative instruction`() {
        val r=parse("下周三下午第5、6节增加人工智能实验课，第8至12周，在实验楼305")
        val e=r.pendingExceptions.single()
        assertEquals("人工智能实验课",e.courseName)
        assertEquals(setOf(8,9,10,11,12),e.declaredWeeks)
        assertEquals(setOf(5,6),e.exception.sections)
        assertEquals("实验楼305",e.exception.room)
        assertNull(e.exception.customTime)
        assertNull(e.exception.teacher)
        assertEquals(LocalDate.of(2027,1,6),r.relativeDates.single().candidate)
        assertTrue(r.unparsedSources.any { it.rawText.contains("下午") })
    }
    @Test fun `missing year never uses reference year automatically multiple clauses and unknown text survive`() {
        val a=parse("1月6日示例数学加课；周末示例物理停课\n近期安排另行通知")
        assertEquals(2,a.pendingExceptions.size)
        assertTrue(a.relativeDates.any { it.originalToken=="1月6日" && it.candidate==null })
        assertTrue(a.relativeDates.any { it.originalToken=="周末" && it.candidate==null })
        assertTrue(a.unparsedSources.any { it.rawText.contains("近期安排另行通知") })
        val b=parse("1月6日示例数学加课",context.copy(confirmedYear=2027))
        assertEquals(LocalDate.of(2027,1,6),b.relativeDates.single().candidate)
    }
    @Test fun `date weekday conflict stays reviewable and context changes proposals deterministically`() {
        val text="明日示例数学加课"
        assertEquals(parse(text),parse(text))
        assertNotEquals(parse(text).relativeDates,parse(text,context.copy(referenceDate=LocalDate.of(2027,1,1))).relativeDates)
        assertTrue(parse("2026-12-30周一示例数学加课").issues.any { it.code=="DATE_WEEKDAY_MISMATCH" })
        assertTrue(parse("下次示例数学停课").relativeDates.single().unresolvedReason!!.isNotBlank())
    }
    @Test fun `bounds reject whole notice and cancellation is not swallowed`() {
        assertNull(parse("明日示例数学加课",context.copy(formatContext=context.formatContext.copy(limits=ImportLimits(maxTextUnits=2)))).draft)
        assertNull(parse("今日甲加课;明日乙加课",context.copy(formatContext=context.formatContext.copy(limits=ImportLimits(maxExceptions=1)))).draft)
        try { parse("明日甲加课",context.copy(formatContext=context.formatContext.copy(cancellation=ImportCancellation { throw CancellationException() }))); fail() } catch (_:CancellationException) { }
    }

    @Test fun `term month day candidate crosses year and overflow remains unresolved`() {
        val r=parse("1月6日示例数学加课",context.copy(confirmedTermMonday=LocalDate.of(2026,9,7)))
        assertEquals(LocalDate.of(2027,1,6),r.relativeDates.single().candidate)
        assertEquals(LocalDate.of(2026,9,7),r.relativeDates.single().basisDate)
        val overflow=parse("明日示例数学加课",context.copy(referenceDate=LocalDate.of(9999,12,31)))
        assertNull(overflow.relativeDates.single().candidate)
        assertTrue(overflow.issues.any { it.code=="DATE_OVERFLOW" })
    }

    @Test fun `notice context validates Monday and year and section labels remain bounded`() {
        try { context.copy(confirmedTermMonday=LocalDate.of(2026,9,8)); fail() } catch(_:IllegalArgumentException) { }
        try { context.copy(confirmedYear=0); fail() } catch(_:IllegalArgumentException) { }
        try { context.copy(confirmedTermMonday=LocalDate.of(0,12,25)); fail() } catch(_:IllegalArgumentException) { }
        val r=parse("今日课程：示例实验 教师：示例老师 班级：甲组 项目：项目甲 教室：示例室 第1、3节 加课")
        val p=r.pendingExceptions.single()
        assertEquals("示例实验",p.courseName)
        assertEquals("示例老师",p.teacher)
        assertEquals("甲组",p.group)
        assertEquals("项目甲",p.experimentProject)
        assertEquals(setOf(1,3),p.exception.sections)
    }

    @Test fun `ambiguous dates remain proposals without silently choosing an operation date`() {
        val r=parse("今日明日示例数学加课；2026-12-30和2026-12-31示例物理停课")
        assertEquals(2,r.relativeDates.size)
        assertTrue(r.pendingExceptions.all { it.exception.targetDate==null && it.exception.originalDate==null })
        assertTrue(r.issues.any { it.code=="AMBIGUOUS_RELATIVE_DATE" })
        assertTrue(r.issues.any { it.code=="AMBIGUOUS_NOTICE_DATES" })
    }

    @Test fun `competing invalid ISO declaration never leaves the valid operation date chosen`() {
        for(marker in listOf("停课","加课")) {
            val text="2026-12-30和2026-02-30示例数学$marker"
            val r=parse(text)
            assertNull(r.pendingExceptions.single().exception.originalDate)
            assertNull(r.pendingExceptions.single().exception.targetDate)
            assertTrue(r.issues.any { it.code=="AMBIGUOUS_NOTICE_DATES" })
            assertTrue(r.issues.any { it.code=="INVALID_DATE" })
            assertEquals(text,r.sources.single().rawText)
        }
    }

    @Test fun `mixed relative and ISO reschedules preserve roles in both directions`() {
        for(prefix in listOf("", "原")) {
            val text="${prefix}明日示例数学调至2027-01-02 10:00-11:30"
            val r=parse(text)
            val p=r.pendingExceptions.single()
            assertEquals(LocalDate.of(2026,12,31),p.exception.originalDate)
            assertEquals(LocalDate.of(2027,1,2),p.exception.targetDate)
            assertEquals("示例数学",p.courseName)
            assertEquals(text,r.sources.single().rawText)
            assertTrue(r.relativeDates.single().requiresReview)
            assertTrue(r.draft!!.requiresConfirmation)
        }
        val inverse=parse("原2026-12-29示例数学调至明日")
        assertEquals(LocalDate.of(2026,12,29),inverse.pendingExceptions.single().exception.originalDate)
        assertEquals(LocalDate.of(2026,12,31),inverse.pendingExceptions.single().exception.targetDate)
    }

    @Test fun `confirmed month day and ISO declarations retain both reschedule directions`() {
        val c=context.copy(confirmedYear=2027)
        val a=parse("原1月1日示例数学调至2027-01-02",c)
        assertEquals(LocalDate.of(2027,1,1),a.pendingExceptions.single().exception.originalDate)
        assertEquals(LocalDate.of(2027,1,2),a.pendingExceptions.single().exception.targetDate)
        assertEquals("示例数学",a.pendingExceptions.single().courseName)
        val b=parse("原2027-01-01示例数学调至1月2日",c)
        assertEquals(LocalDate.of(2027,1,1),b.pendingExceptions.single().exception.originalDate)
        assertEquals(LocalDate.of(2027,1,2),b.pendingExceptions.single().exception.targetDate)
        assertTrue(a.relativeDates.single().requiresReview)
        assertTrue(b.relativeDates.single().requiresReview)
    }

    @Test fun `unresolved original declarations never shift a valid target into original`() {
        for(token in listOf("1月1日","周末","2026-02-30")) {
            val text="原${token}示例数学调至2027-01-02"
            val r=parse(text)
            val p=r.pendingExceptions.single()
            assertNull(p.exception.originalDate)
            assertEquals(LocalDate.of(2027,1,2),p.exception.targetDate)
            assertEquals("示例数学",p.courseName)
            assertTrue(ImportField.ORIGINAL_DATE in p.exception.missingFields)
            assertEquals(text,r.sources.single().rawText)
        }
    }

    @Test fun `invalid month day target preserves valid relative original`() {
        val r=parse("原明日示例数学调至2月30日",context.copy(confirmedYear=2027))
        assertEquals(LocalDate.of(2026,12,31),r.pendingExceptions.single().exception.originalDate)
        assertNull(r.pendingExceptions.single().exception.targetDate)
        assertEquals(2,r.relativeDates.size)
        assertTrue(r.relativeDates.all { it.requiresReview })
        assertTrue(r.relativeDates.any { it.unresolvedReason=="INVALID_DATE" })
    }

    @Test fun `single target date is never promoted to original date`() {
        val r=parse("示例数学调至2027-01-02")
        assertNull(r.pendingExceptions.single().exception.originalDate)
        assertEquals(LocalDate.of(2027,1,2),r.pendingExceptions.single().exception.targetDate)
    }

    @Test fun `extra mixed declarations and same side dates stay ambiguous without shifting`() {
        for(text in listOf("原明日示例数学调至2027-01-02或1月3日","原明日2027-01-02示例数学调至", "原2026-02-30明日示例数学调至2027-01-02")) {
            val r=parse(text,context.copy(confirmedYear=2027))
            assertNull(r.pendingExceptions.single().exception.originalDate)
            assertNull(r.pendingExceptions.single().exception.targetDate)
            assertTrue(r.issues.any { it.code=="AMBIGUOUS_NOTICE_DATES" })
            assertEquals(text,r.sources.single().rawText)
            assertTrue(r.relativeDates.isNotEmpty())
            assertTrue(r.draft!!.requiresConfirmation)
        }
    }

    @Test fun `two relative declarations retain lexical reschedule roles`() {
        val r=parse("原今日示例数学调至明日")
        assertEquals(LocalDate.of(2026,12,30),r.pendingExceptions.single().exception.originalDate)
        assertEquals(LocalDate.of(2026,12,31),r.pendingExceptions.single().exception.targetDate)
        assertEquals(2,r.relativeDates.size)
    }

    @Test fun `multiple sparse section ranges preserve every declared section`() {
        for(token in listOf("第1-2、5-6节","第1至2、5至6节","第1、2、5-6节")) {
            val r=parse("今日示例数学${token}加课")
            assertEquals(token,setOf(1,2,5,6),r.pendingExceptions.single().exception.sections)
        }
    }

    @Test fun `oversized and malformed section expressions never salvage a valid suffix`() {
        for(token in listOf("第1001节","第101节","第1-1001节","第1--2节","第1/2节","第1-2/5-6节","第1、节","第-1节","第1.2节")) {
            val text="今日示例数学${token}加课"
            val r=parse(text)
            assertNull(token,r.pendingExceptions.single().exception.sections)
            assertTrue(token,r.issues.any { it.code=="INVALID_SECTIONS" })
            assertEquals(text,r.sources.single().rawText)
        }
    }

    @Test fun `section upper bound and original course prefix remain exact`() {
        val r=parse("今日原理数学第99-100节加课")
        assertEquals(setOf(99,100),r.pendingExceptions.single().exception.sections)
        assertEquals("原理数学",r.pendingExceptions.single().courseName)
    }

    @Test fun `section whitespace belongs to the whole numeric declaration`() {
        for((token,expected) in listOf("第1、 3节" to setOf(1,3),"第1 - 2节" to setOf(1,2),"第1 - 2、 5 - 6节" to setOf(1,2,5,6))) {
            assertEquals(token,expected,parse("今日示例数学${token}加课").pendingExceptions.single().exception.sections)
        }
        for(token in listOf("第1 / 2节","第- 1节","第100 1节")) {
            val r=parse("今日课程：示例数学 ${token} 加课")
            assertNull(token,r.pendingExceptions.single().exception.sections)
            assertTrue(token,r.issues.any { it.code=="INVALID_SECTIONS" })
        }
    }

    @Test fun `week declarations retain sparse ranges whitespace and parity`() {
        for((token,expected) in listOf("第1-2、5-6周" to setOf(1,2,5,6),"第1 - 2、 5 - 6周" to setOf(1,2,5,6),"第1至3周（单）、 第6周" to setOf(1,3,6))) {
            assertEquals(token,expected,parse("今日课程：示例数学 ${token} 周一 第1节 加课").pendingExceptions.single().declaredWeeks)
        }
    }

    @Test fun `oversized and malformed week declarations never salvage suffixes`() {
        for(token in listOf("第10001周","第1 / 2周","第- 1周","第100 1周","第1--2、5-6周")) {
            val text="今日课程：示例数学 ${token} 周一 第1节 加课"
            val r=parse(text)
            assertEquals(token,emptySet<Int>(),r.pendingExceptions.single().declaredWeeks)
            assertTrue(token,r.issues.any { it.code=="INVALID_WEEKS" })
            assertEquals(text,r.sources.single().rawText)
        }
    }

    @Test fun `oversized ISO declarations never promote a valid date substring`() {
        for(token in listOf("12026-12-30","2026-112-30","2026-12-300")) {
            val text="课程：示例数学 ${token} 加课"
            val r=parse(text)
            assertNull(token,r.pendingExceptions.single().exception.targetDate)
            assertEquals(text,r.sources.single().rawText)
            assertTrue(r.draft!!.requiresConfirmation)
        }
    }

    @Test fun `oversized clocks never promote a valid time substring`() {
        for(token in listOf("110:00-11:30","10:00-11:300","10:000-11:30","10:00:000-11:30")) {
            val text="今日课程：示例数学 ${token} 第1节 加课"
            val r=parse(text)
            assertNull(token,r.pendingExceptions.single().exception.customTime)
            assertEquals(setOf(1),r.pendingExceptions.single().exception.sections)
            assertEquals(text,r.sources.single().rawText)
        }
    }

    @Test fun `explicit clock followed by section and week declarations retains independent tokens`() {
        val r=parse("今日课程：示例数学 10:00-11:30 第1、 3节 第1 - 2周 加课")
        assertEquals("10:00",r.pendingExceptions.single().exception.customTime!!.start.toString())
        assertEquals(setOf(1,3),r.pendingExceptions.single().exception.sections)
        assertEquals(setOf(1,2),r.pendingExceptions.single().declaredWeeks)
    }

    @Test fun `unsupported repeated prefix ranges never promote the last term`() {
        for(unit in listOf("节","周")) {
            val r=parse("今日课程：示例数学 第1 - 第2${unit} 加课")
            assertNull(r.pendingExceptions.single().exception.sections)
            assertTrue(r.pendingExceptions.single().declaredWeeks.isEmpty())
            assertTrue(r.issues.any { it.code==if(unit=="节") "INVALID_SECTIONS" else "INVALID_WEEKS" })
        }
    }

    @Test fun `long numeric declarations remain bounded reviewable inputs`() {
        for(unit in listOf("节","周")) {
            val text="课程：示例数学 第"+"1,".repeat(1500)+"1${unit} 加课"
            val r=parse(text)
            assertNotNull(r.draft)
            assertNull(r.pendingExceptions.single().exception.sections)
            assertTrue(r.pendingExceptions.single().declaredWeeks.isEmpty())
            assertTrue(r.issues.any { it.code==if(unit=="节") "INVALID_SECTIONS" else "INVALID_WEEKS" })
            assertEquals(text,r.sources.single().rawText)
            assertTrue(r.draft!!.requiresConfirmation)
        }
    }

    @Test fun `ISO date followed by numeric declarations keeps independent tokens`() {
        val r=parse("课程：示例数学 2026-12-30 第1 - 2周 第1、 3节 加课")
        assertEquals(LocalDate.of(2026,12,30),r.pendingExceptions.single().exception.targetDate)
        assertEquals(setOf(1,2),r.pendingExceptions.single().declaredWeeks)
        assertEquals(setOf(1,3),r.pendingExceptions.single().exception.sections)
    }

    @Test fun `comma separated week and section declarations retain their independent units`() {
        for(tokens in listOf("第1周，第2节","第2节，第1周","第1-2周，第2、4节，第5周，第6节")) {
            val text="今日课程：示例数学 ${tokens} 加课"
            val r=parse(text)
            val p=r.pendingExceptions.single()
            assertEquals(tokens,if(tokens.contains("第5周")) setOf(1,2,5) else setOf(1),p.declaredWeeks)
            assertEquals(tokens,if(tokens.contains("第6节")) setOf(2,4,6) else setOf(2),p.exception.sections)
            assertEquals(text,r.sources.single().rawText)
        }
    }

    @Test fun `same unit continuation retains exact grammar and rejects malformed complete expressions`() {
        val r=parse("今日课程：示例数学 第1周，第3周 第2、4节 加课")
        assertEquals(setOf(1,3),r.pendingExceptions.single().declaredWeeks)
        assertEquals(setOf(2,4),r.pendingExceptions.single().exception.sections)
        val invalid=parse("今日课程：示例数学 第1周，第2 - 第3周 第2节 加课")
        assertTrue(invalid.pendingExceptions.single().declaredWeeks.isEmpty())
        assertEquals(setOf(2),invalid.pendingExceptions.single().exception.sections)
        assertTrue(invalid.issues.any { it.code=="INVALID_WEEKS" })
    }

    @Test fun `long colon declarations never overflow or promote their clock suffix`() {
        val text="课程：示例数学 "+"1:".repeat(1500)+"1-10:00 加课"
        val r=parse(text)
        assertNotNull(r.draft)
        assertNull(r.pendingExceptions.single().exception.customTime)
        assertEquals(text,r.sources.single().rawText)
        assertTrue(r.draft!!.requiresConfirmation)
    }

    @Test fun `extra colon components never promote a shorter valid clock`() {
        for(token in listOf("10:00:00:00-11:30","10:00-11:30:00:00","10:00:00:00:00-11:30")) {
            val r=parse("今日课程：示例数学 ${token} 第1节 加课")
            assertNull(token,r.pendingExceptions.single().exception.customTime)
            assertEquals(setOf(1),r.pendingExceptions.single().exception.sections)
        }
    }

    @Test fun `invalid reschedule original or target retains its lexical date role`() {
        val first="原2026-02-30示例数学调至2026-10-06"
        val a=parse(first)
        assertNull(a.pendingExceptions.single().exception.originalDate)
        assertEquals(LocalDate.of(2026,10,6),a.pendingExceptions.single().exception.targetDate)
        assertEquals(first,a.sources.single().rawText)
        val second="原2026-10-05示例数学调至2026-02-30"
        val b=parse(second)
        assertEquals(LocalDate.of(2026,10,5),b.pendingExceptions.single().exception.originalDate)
        assertNull(b.pendingExceptions.single().exception.targetDate)
        assertEquals(second,b.sources.single().rawText)
        assertTrue(a.issues.any { it.code=="INVALID_DATE" })
        assertTrue(b.issues.any { it.code=="INVALID_DATE" })
    }
}
