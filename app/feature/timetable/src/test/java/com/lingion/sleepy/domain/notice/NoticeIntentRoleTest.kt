/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.notice

import com.lingion.sleepy.importing.NoticeBigSection
import com.lingion.sleepy.importing.NoticeBigSectionMapping
import com.lingion.sleepy.importing.NoticeDateBasis
import com.lingion.sleepy.importing.NoticeDayPart
import com.lingion.sleepy.importing.NoticeIntentKind
import com.lingion.sleepy.importing.NoticeRoomRole
import com.lingion.sleepy.importing.formats.ImportLimits
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class NoticeIntentRoleTest {
    private val basis = NoticeFixtures.basis

    @Test fun `the full example keeps old and new roles and only the tenth week`() {
        val intent = NoticeFixtures.intent("本周五的数据可视化课程由原来的第3-4节调整为第5-6节，教室不变。")
        assertEquals(NoticeIntentKind.RESCHEDULE_TIME, intent.kind)
        assertEquals(setOf(3, 4), intent.original.sections)
        assertEquals(setOf(5, 6), intent.target.sections)
        assertEquals(LocalDate.of(2026, 11, 13), intent.originalDate)
        assertEquals(LocalDate.of(2026, 11, 13), intent.targetDate)
        assertEquals(NoticeRoomRole.KEEP, intent.roomChange?.role)
        assertEquals("数据可视化", intent.courseNameHint)
        assertEquals("REFERENCE_ISO_WEEK_DAY", intent.dateRule)
        assertTrue(intent.sameDayAdjustment)
        assertNull(intent.original.time)
        assertNull(intent.target.time)
    }

    @Test fun `改为 is a reschedule verb and never joins both section groups`() {
        val intent = NoticeFixtures.intent("本周五数据可视化由第3-4节改为第5-6节")
        assertEquals(NoticeIntentKind.RESCHEDULE_TIME, intent.kind)
        assertEquals(setOf(3, 4), intent.original.sections)
        assertEquals(setOf(5, 6), intent.target.sections)
    }

    @Test fun `old and new clocks stay in their lexical roles`() {
        val intent = NoticeFixtures.intent("本周五数据可视化由第3-4节(10:00-11:40)调整为第5-6节(14:00-15:40)")
        assertEquals(LocalTime.of(10, 0), intent.original.time?.start)
        assertEquals(LocalTime.of(11, 40), intent.original.time?.end)
        assertEquals(LocalTime.of(14, 0), intent.target.time?.start)
        assertEquals(LocalTime.of(15, 40), intent.target.time?.end)
        assertEquals(setOf(3, 4), intent.original.sections)
        assertEquals(setOf(5, 6), intent.target.sections)
    }

    @Test fun `same day time only change keeps the original date explicitly`() {
        val intent = NoticeFixtures.intent("原2026-11-13数据可视化调至14:00-15:30")
        assertEquals(NoticeIntentKind.RESCHEDULE_TIME, intent.kind)
        assertEquals(LocalDate.of(2026, 11, 13), intent.originalDate)
        assertEquals(LocalDate.of(2026, 11, 13), intent.targetDate)
        assertEquals(LocalTime.of(15, 30), intent.target.time?.end)
        assertTrue(intent.sameDayAdjustment)
    }

    @Test fun `cross day reschedule keeps both dates and the target time`() {
        val intent = NoticeFixtures.intent("原2026-11-13数据可视化调至2026-11-14 10:00-11:30")
        assertEquals(NoticeIntentKind.CHANGE_DATE, intent.kind)
        assertEquals(LocalDate.of(2026, 11, 13), intent.originalDate)
        assertEquals(LocalDate.of(2026, 11, 14), intent.targetDate)
        assertEquals(LocalTime.of(10, 0), intent.target.time?.start)
    }

    @Test fun `cancel is its own intent and never becomes a course deletion`() {
        val intent = NoticeFixtures.intent("2026-11-20数据可视化停课")
        assertEquals(NoticeIntentKind.CANCEL, intent.kind)
        assertEquals(LocalDate.of(2026, 11, 20), intent.originalDate)
        assertNull(intent.targetDate)
        assertFalse(intent.target.specified)
    }

    @Test fun `extra adds one occurrence and removes nothing`() {
        val intent = NoticeFixtures.intent("2026-11-20 14:00-15:30 数据可视化补课")
        assertEquals(NoticeIntentKind.EXTRA, intent.kind)
        assertNull(intent.originalDate)
        assertEquals(LocalDate.of(2026, 11, 20), intent.targetDate)
        assertEquals(LocalTime.of(14, 0), intent.target.time?.start)
    }

    @Test fun `room only keeps date sections and time and changes only the room`() {
        val intent = NoticeFixtures.intent("2026-11-13数据可视化第3-4节教室改为B203")
        assertEquals(NoticeIntentKind.ROOM_ONLY, intent.kind)
        assertEquals(NoticeRoomRole.CHANGE, intent.roomChange?.role)
        assertEquals("B203", intent.roomChange?.to)
        assertEquals(setOf(3, 4), intent.original.sections)
        assertEquals(LocalDate.of(2026, 11, 13), intent.originalDate)
        assertFalse(intent.target.specified)
    }

    @Test fun `week and weekday without a confirmed term need an explicit basis`() {
        val results = NoticeIntentParser.parse("第12周周五数据可视化停课", NoticeBasis(NoticeFixtures.emptyBasis))
        val need = results.single()
        assertTrue("$need", need is NoticeIntentResult.NeedBasis)
        assertEquals(setOf(NoticeBasisField.TERM_MONDAY, NoticeBasisField.TOTAL_WEEKS), (need as NoticeIntentResult.NeedBasis).missing)

        val ready = NoticeIntentParser.parse("第12周周五数据可视化停课", NoticeBasis(basis)).single()
        assertTrue("$ready", ready is NoticeIntentResult.Ready)
        val intent = (ready as NoticeIntentResult.Ready).intent
        assertEquals(LocalDate.of(2026, 11, 27), intent.originalDate)
        assertEquals("CONFIRMED_TERM_WEEK_DAY", intent.dateRule)
    }

    @Test fun `relative wording without a confirmed reference date is NeedBasis`() {
        val results = NoticeIntentParser.parse("本周五数据可视化由第3-4节调整为第5-6节", NoticeBasis(NoticeFixtures.emptyBasis))
        val need = results.single()
        assertTrue("$need", need is NoticeIntentResult.NeedBasis)
        assertTrue((need as NoticeIntentResult.NeedBasis).missing.contains(NoticeBasisField.REFERENCE_DATE))
    }

    @Test fun `sunday and monday boundaries use the confirmed reference week`() {
        val wednesday = NoticeDateBasis(referenceDate = LocalDate.of(2026, 11, 11), termMonday = NoticeFixtures.termMonday, totalWeeks = 16)
        assertEquals(LocalDate.of(2026, 11, 15), NoticeFixtures.intent("本周日数据可视化停课", wednesday).originalDate)
        assertEquals(LocalDate.of(2026, 11, 16), NoticeFixtures.intent("下周一数据可视化停课", wednesday).originalDate)
    }

    @Test fun `afternoon second big section needs a confirmed mapping instead of guessed periods`() {
        val results = NoticeIntentParser.parse("本周五下午第二大节数据可视化加课", NoticeBasis(basis))
        val need = results.single()
        assertTrue("$need", need is NoticeIntentResult.NeedTime)
        assertEquals(NoticeCodes.NEED_PERIOD_MAPPING, (need as NoticeIntentResult.NeedTime).code)
        assertEquals(NoticeBigSection(NoticeDayPart.AFTERNOON, 2), need.bigSection)

        val mapping = NoticeBigSectionMapping(afternoon = listOf(setOf(5, 6), setOf(7, 8)))
        val intent = NoticeFixtures.intent("本周五下午第二大节数据可视化加课", mapping = mapping)
        assertEquals(NoticeIntentKind.EXTRA, intent.kind)
        assertEquals(setOf(7, 8), intent.target.sections)
    }

    @Test fun `bare afternoon without any exact time is not ready`() {
        val results = NoticeIntentParser.parse("本周五下午数据可视化加课", NoticeBasis(basis))
        val need = results.single()
        assertTrue("$need", need is NoticeIntentResult.NeedTime)
        assertEquals(NoticeCodes.NEED_TIME, (need as NoticeIntentResult.NeedTime).code)
        assertNull(need.bigSection)
    }

    @Test fun `contradictory room declarations stay unresolved`() {
        val result = NoticeIntentParser.parse("2026-11-13数据可视化教室不变且教室改为B203", NoticeBasis(basis)).single()
        assertTrue("$result", result is NoticeIntentResult.Blocked)
        assertTrue((result as NoticeIntentResult.Blocked).problems.any { it.code == NoticeCodes.AMBIGUOUS_NOTICE })
    }

    @Test fun `limits and cancellation still stop the intent parser`() {
        val limited = NoticeIntentParser.parse(
            "本周五数据可视化由第3-4节调整为第5-6节",
            NoticeBasis(basis),
            ImportLimits(maxTextUnits = 2),
        ).single()
        assertTrue("$limited", limited is NoticeIntentResult.Blocked)
        assertTrue((limited as NoticeIntentResult.Blocked).problems.any { it.code == NoticeCodes.LIMIT_EXCEEDED })

        try {
            NoticeIntentParser.parse(
                "本周五数据可视化由第3-4节调整为第5-6节",
                NoticeBasis(basis),
                cancellation = com.lingion.sleepy.importing.formats.ImportCancellation { throw java.util.concurrent.CancellationException() },
            )
            fail("cancellation must propagate")
        } catch (_: java.util.concurrent.CancellationException) { }
    }
}