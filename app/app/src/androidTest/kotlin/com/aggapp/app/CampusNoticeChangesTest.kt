package com.aggapp.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aggapp.app.ui.MainActivity
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.repository.ScheduleRepository
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real notice UI and Room; repository writes only seed/clean this test's synthetic course. */
@RunWith(AndroidJUnit4::class)
class CampusNoticeChangesTest {
    @Test(timeout = 180_000)
    fun cancellationNoticeRequiresOriginalArrangementThenOnlyCancelsSelectedDate() {
        withSyntheticCourse("C") { fixture ->
            val ui = fixture.ui
            importReviewedChange(fixture, "${fixture.originalDate} ${fixture.name} 停课", "CANCEL")

            val saved = snapshot(fixture.repository)
            assertOriginalDataPreserved(fixture.before, saved, setOf(fixture.courseId))
            assertNoOccurrence(saved, fixture.courseId, fixture.originalDate)
            assertOccurrence(saved, fixture.courseId, fixture.unchangedDate, BASE_TIME)
            assertNoOccurrence(saved, fixture.courseId, fixture.targetDate)

            ui.click("查看课表")
            showDate(ui, fixture.originalDate)
            assertFalse("Cancelled course must disappear on its original date", ui.hasText(fixture.name))
            showDate(ui, fixture.unchangedDate)
            ui.revealText(fixture.name)
            ui.waitContaining("09:00–10:00")

            ui.recreateActivity()
            ui.waitText("本机课表")
            ui.waitText(fixture.unchangedDate.toString())
            ui.revealText(fixture.name)
            assertTrue("Saved cancellation must survive Activity recreation", saved == snapshot(resumedRepository()))
            showDate(ui, fixture.originalDate)
            assertFalse("Recreation must not restore the cancelled occurrence", ui.hasText(fixture.name))
            ui.click("回到今天")
        }
    }

    @Test(timeout = 180_000)
    fun rescheduleNoticeRequiresOriginalArrangementThenMovesDateAndTime() {
        withSyntheticCourse("R") { fixture ->
            val ui = fixture.ui
            importReviewedChange(
                fixture,
                "原${fixture.originalDate} ${fixture.name} 调至${fixture.targetDate} 10:00-11:30",
                "RESCHEDULE",
            )

            val saved = snapshot(fixture.repository)
            assertOriginalDataPreserved(fixture.before, saved, setOf(fixture.courseId))
            assertNoOccurrence(saved, fixture.courseId, fixture.originalDate)
            assertOccurrence(saved, fixture.courseId, fixture.targetDate, MOVED_TIME)
            assertOccurrence(saved, fixture.courseId, fixture.unchangedDate, BASE_TIME)

            ui.click("查看课表")
            showDate(ui, fixture.originalDate)
            assertFalse("Rescheduled course must leave its original date", ui.hasText(fixture.name))
            showDate(ui, fixture.targetDate)
            ui.revealText(fixture.name)
            ui.waitContaining("10:00–11:30")
            assertFalse("Target date must use the reviewed replacement time", ui.hasText("09:00–10:00"))
            showDate(ui, fixture.unchangedDate)
            ui.revealText(fixture.name)
            ui.waitContaining("09:00–10:00")
            showDate(ui, fixture.targetDate)

            ui.recreateActivity()
            ui.waitText("本机课表")
            ui.waitText(fixture.targetDate.toString())
            ui.revealText(fixture.name)
            ui.waitContaining("10:00–11:30")
            assertTrue("Saved reschedule must survive Activity recreation", saved == snapshot(resumedRepository()))
            showDate(ui, fixture.originalDate)
            assertFalse("Recreation must not restore the moved original occurrence", ui.hasText(fixture.name))
            ui.click("回到今天")
        }
    }

    private fun importReviewedChange(fixture: Fixture, notice: String, kind: String) {
        val ui = fixture.ui
        ui.click("导入课表")
        ui.click("通知文字")
        ui.enter("粘贴通知", notice)
        ui.click("读取内容")
        ui.revealText("选择要关联的安排：")
        val noticeDates = if (kind == "CANCEL") fixture.originalDate.toString() else "${fixture.originalDate} ${fixture.targetDate}"
        ui.waitContaining("通知：$kind $noticeDates")
        assertUnwritten(fixture, "Reading the notice must not write to Room")

        // One batch action reviews source and pending-record evidence.
        // The title alone must never attach the notice to an existing course.
        ui.click("核对并预览")
        ui.revealText(ATTACHMENT_REVIEW)
        assertFalse("Reviewed evidence must still require an explicit original arrangement", ui.hasText("导入预览"))
        assertUnwritten(fixture, "Missing original-arrangement attachment must not write")

        // Exact synthetic course, both original dates and the original time identify the base.
        // This is the current UI label from meetingScheduleSummary + logicalBaseSummary.
        val dates = listOf(fixture.originalDate, fixture.unchangedDate).sorted().joinToString("、")
        val time = "09:00–10:00"
        ui.click("已有课程：${fixture.name} · 指定日期 $dates · $time · $time · 合成关联")
        ui.waitText("已选择关联；请核对原日期与目标日期。")
        ui.click("核对并预览")
        ui.waitText("导入预览")
        assertUnwritten(fixture, "Preview must not write")
        ui.click("继续确认")
        ui.waitText("确认追加课程？")
        assertUnwritten(fixture, "Final confirmation must not write before acceptance")
        ui.click("取消")
        ui.waitText("导入预览")
        assertUnwritten(fixture, "Cancelling final confirmation must produce zero writes")
        ui.click("继续确认")
        ui.click("确认追加")
        ui.waitText("导入完成。可关闭此页查看课程。")
    }

    private fun withSyntheticCourse(suffix: String, block: (Fixture) -> Unit) {
        val ui = CampusUiDriver()
        ui.launch()
        ui.waitText("本机课表")
        val repository = resumedRepository()
        val before = snapshot(repository)
        val name = "通知验收$suffix${SystemClock.uptimeMillis()}"
        assertTrue("Synthetic course name must be unused", before.courses.none { it.courseName == name })
        // A separate empty future window avoids conflicts with real courses without changing the term.
        val originalDate = (0L until 32L).map { LocalDate.of(2096, 10, 1).plusDays(it * 14L) }.firstOrNull { date ->
            listOf(date, date.plusDays(1), date.plusDays(7)).all { TimetableRules.occurrencesOn(before, it).isEmpty() }
        } ?: throw AssertionError("No empty synthetic date window; original data was not modified")
        val unchangedDate = originalDate.plusDays(7)
        var courseId: Long? = null
        try {
            val id = runBlocking { withTimeout(10_000) {
                repository.saveCourse(CourseDraft(
                    courseName = name,
                    teacher = "合成教师",
                    room = "合成通知室",
                    meetings = listOf(CourseMeeting(
                        day = 0,
                        weeks = emptySet(),
                        sections = emptySet(),
                        customTime = BASE_TIME,
                        kind = MeetingKind.DATE_SPECIFIC,
                        dates = setOf(originalDate, unchangedDate),
                        experimentProject = "合成关联",
                    )),
                ))
            } }
            courseId = id
            val seeded = snapshot(repository)
            assertOriginalDataPreserved(before, seeded, setOf(id))
            assertOccurrence(seeded, id, originalDate, BASE_TIME)
            assertOccurrence(seeded, id, unchangedDate, BASE_TIME)
            showDate(ui, originalDate)
            ui.revealText(name)
            ui.waitContaining("09:00–10:00")
            block(Fixture(ui, repository, before, seeded, name, id, originalDate, originalDate.plusDays(1), unchangedDate))
        } finally {
            val originalIds = before.courses.map { it.id }.toSet()
            // Exact returned ID, plus any erroneous new import row with our unique synthetic name.
            // Never delete an ID that existed before the test, even if an assertion has failed.
            snapshot(repository).courses.filter {
                it.id !in originalIds && (it.id == courseId || it.courseName == name)
            }.forEach { course -> runBlocking { withTimeout(10_000) { repository.deleteCourse(course.id) } } }
            assertOriginalDataPreserved(before, snapshot(repository), emptySet())
        }
    }

    private fun assertUnwritten(fixture: Fixture, message: String) {
        // Boolean comparison avoids placing private courses or semester names in assertion output.
        assertTrue(message, fixture.seeded == snapshot(fixture.repository))
    }

    private fun assertOriginalDataPreserved(before: TimetableSnapshot, after: TimetableSnapshot, ownedIds: Set<Long>) {
        assertTrue("Existing semester must be unchanged", before.settings == after.settings)
        assertTrue("Existing periods must be unchanged", before.periods == after.periods)
        assertTrue("Timetable scope must be unchanged", before.scopeToken == after.scopeToken)
        assertTrue("All original courses must be unchanged", before.courses == after.courses.filterNot { it.id in ownedIds })
        assertEquals("Only the owned synthetic course may be added", before.courses.size + ownedIds.size, after.courses.size)
    }

    private fun assertNoOccurrence(snapshot: TimetableSnapshot, courseId: Long, date: LocalDate) {
        assertTrue("Synthetic course must have no occurrence on $date", TimetableRules.occurrencesOn(snapshot, date).none { it.course.id == courseId })
    }

    private fun assertOccurrence(snapshot: TimetableSnapshot, courseId: Long, date: LocalDate, time: TimeRange) {
        val occurrences = TimetableRules.occurrencesOn(snapshot, date).filter { it.course.id == courseId }
        assertEquals("Exactly one synthetic occurrence must remain on $date", 1, occurrences.size)
        assertEquals(listOf(time), occurrences.single().timeRanges)
        assertEquals("合成通知室", occurrences.single().effectiveRoom)
        assertEquals("合成关联", occurrences.single().effectiveProject)
    }

    private fun showDate(ui: CampusUiDriver, date: LocalDate) {
        ui.selectTab("今日")
        ui.click("选择日期")
        ui.enter("浏览日期", date.toString())
        ui.click("查看")
        ui.waitText(date.toString())
    }

    private fun resumedRepository(): ScheduleRepository {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var activity: MainActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
        }
        return checkNotNull(activity).timetableDependencies.repository
    }

    private fun snapshot(repository: ScheduleRepository): TimetableSnapshot = runBlocking {
        withTimeout(10_000) { repository.observeTimetable().first() }
    }

    private data class Fixture(
        val ui: CampusUiDriver,
        val repository: ScheduleRepository,
        val before: TimetableSnapshot,
        val seeded: TimetableSnapshot,
        val name: String,
        val courseId: Long,
        val originalDate: LocalDate,
        val targetDate: LocalDate,
        val unchangedDate: LocalDate,
    )

    private companion object {
        const val ATTACHMENT_REVIEW = "请明确停课、调课或加课对应的原安排与日期。"
        val BASE_TIME = TimeRange(LocalTime.of(9, 0), LocalTime.of(10, 0))
        val MOVED_TIME = TimeRange(LocalTime.of(10, 0), LocalTime.of(11, 30))
    }
}
