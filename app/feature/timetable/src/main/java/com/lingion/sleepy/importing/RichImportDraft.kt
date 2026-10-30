/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing

import java.time.LocalDate
import java.time.LocalTime

/** Top-left, rotation-adjusted PDF point coordinates, one-based page number. */
data class TextBox(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class SourcePosition(val page: Int, val bounds: TextBox)
data class PositionedText(val text: String, val position: SourcePosition, val rawText: String = text)
data class PositionedPage(val number: Int, val width: Float, val height: Float, val text: List<PositionedText>)
data class PositionedDocument(val pages: List<PositionedPage>)
data class SourceExcerpt(val position: SourcePosition, val rawText: String) {
    internal val parseText: String get() = PdfTimetableParser.normalize(rawText)
}
enum class MeetingKind { RECURRING, DATE_SPECIFIC, UNSCHEDULED }
enum class ImportField { COURSE_NAME, TEACHER, ROOM, WEEKS, DAY_OF_WEEK, TIME, SEMESTER_START_DATE, SEMESTER_ID, GROUP, EXPERIMENT_PROJECT, ORIGINAL_DATE, TARGET_DATE }
data class ParseIssue(val code: String, val message: String, val source: SourceExcerpt? = null)
data class DraftTimeRange(val start: LocalTime, val end: LocalTime)
data class PeriodProposal(val section: Int, val time: DraftTimeRange, val sources: List<SourceExcerpt>)

enum class RichExceptionKind { CANCEL, RESCHEDULE, EXTRA }
/** Null overrides inherit. An explicit empty metadata string clears that value. */
data class RichExceptionDraft(
    val kind: RichExceptionKind,
    val originalDate: LocalDate? = null,
    val targetDate: LocalDate? = null,
    val customTime: DraftTimeRange? = null,
    val sections: Set<Int>? = null,
    val teacher: String? = null,
    val room: String? = null,
    val experimentProject: String? = null,
    val group: String? = null,
    val sources: List<SourceExcerpt> = emptyList(),
    val warnings: List<ParseIssue> = emptyList(),
    val missingFields: Set<ImportField> = emptySet(),
)
data class RichMeetingDraft(
    val kind: MeetingKind,
    val dayOfWeek: Int? = null,
    val weeks: Set<Int> = emptySet(),
    val sections: Set<Int> = emptySet(),
    val customTime: DraftTimeRange? = null,
    val dates: Set<LocalDate> = emptySet(),
    val teacher: String? = null,
    val room: String? = null,
    val group: String? = null,
    val experimentProject: String? = null,
    val isExperiment: Boolean = false,
    val sources: List<SourceExcerpt> = emptyList(),
    val warnings: List<ParseIssue> = emptyList(),
    val missingFields: Set<ImportField> = emptySet(),
    val exceptions: List<RichExceptionDraft> = emptyList(),
)
data class RichCourseDraft(
    val name: String,
    val sourceCourseCode: String? = null,
    val sourceClassCode: String? = null,
    val meetings: List<RichMeetingDraft> = emptyList(),
    val sources: List<SourceExcerpt> = emptyList(),
    val warnings: List<ParseIssue> = emptyList(),
    val missingFields: Set<ImportField> = emptySet(),
)
/** Read-only parser DTO. No implicit conversion to CourseDraft or persistence authorization. */
data class RichTimetableDraft(
    val courses: List<RichCourseDraft> = emptyList(),
    val periodProposals: List<PeriodProposal> = emptyList(),
    val issues: List<ParseIssue> = emptyList(),
    val unparsed: List<SourceExcerpt> = emptyList(),
    val missingFields: Set<ImportField> = setOf(ImportField.SEMESTER_ID, ImportField.SEMESTER_START_DATE),
    val adapterIds: Set<String> = emptySet(),
) {
    val requiresConfirmation: Boolean get() = missingFields.isNotEmpty() || issues.isNotEmpty() || unparsed.isNotEmpty() || courses.any { c ->
        c.warnings.isNotEmpty() || c.missingFields.isNotEmpty() || c.meetings.any { m -> m.warnings.isNotEmpty() || m.missingFields.isNotEmpty() || m.exceptions.any { it.warnings.isNotEmpty() || it.missingFields.isNotEmpty() } }
    }
}

