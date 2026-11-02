/* SPDX-License-Identifier: GPL-3.0-only
 * Bounded adapter from the existing notice parser to a role-complete, basis-explicit intent.
 * It reuses NoticeTimetableParser for every date/week/section token; it never parses its own
 * date grammar and never invents a reference date from the device clock.
 */
package com.lingion.sleepy.domain.notice

import com.lingion.sleepy.importing.NoticeBigSection
import com.lingion.sleepy.importing.NoticeIntentKind
import com.lingion.sleepy.importing.NoticeScope
import com.lingion.sleepy.importing.RichExceptionKind
import com.lingion.sleepy.importing.formats.FormatParseContext
import com.lingion.sleepy.importing.formats.ImportCancellation
import com.lingion.sleepy.importing.formats.ImportLimits
import com.lingion.sleepy.importing.formats.NoticeParseContext
import com.lingion.sleepy.importing.formats.NoticeTimetableParser
import com.lingion.sleepy.importing.formats.PendingExceptionAttachment
import java.time.LocalDate

object NoticeIntentParser {
    private val relativeTokens = Regex("今日|今天|明日|明天|后天|(?:本周|下周)[一二三四五六日天]?|周末|近期|下次")
    private val weekTokens = Regex("(?:第|[0-9])[0-9、，,~～至\\- ]*周")
    private val weekdayTokens = Regex("(?:星期|周)[一二三四五六日天]")
    private val monthDayTokens = Regex("[0-9]{1,2}月[0-9]{1,2}日")
    private val dayPartTokens = Regex("上午|下午|晚上|中午|傍晚")

    /** Basis fields the text actually needs; a missing one is a NEED_BASIS, never a default. */
    fun requiredBasisFields(text: String): Set<NoticeBasisField> = buildSet {
        if (relativeTokens.containsMatchIn(text)) add(NoticeBasisField.REFERENCE_DATE)
        if (weekTokens.containsMatchIn(text) && weekdayTokens.containsMatchIn(text)) {
            add(NoticeBasisField.TERM_MONDAY)
            add(NoticeBasisField.TOTAL_WEEKS)
        }
        if (monthDayTokens.containsMatchIn(text)) {
            if (!weekTokens.containsMatchIn(text)) add(NoticeBasisField.YEAR)
        }
    }

    fun parse(
        text: String,
        basis: NoticeBasis,
        limits: ImportLimits = ImportLimits(),
        cancellation: ImportCancellation = ImportCancellation {},
        maxWeek: Int? = null,
    ): List<NoticeIntentResult> {
        cancellation.check()
        if (text.length > limits.maxTextUnits || text.toByteArray(Charsets.UTF_8).size > limits.maxInputBytes) {
            return listOf(NoticeIntentResult.Blocked(listOf(NoticeProblem(NoticeCodes.LIMIT_EXCEEDED))))
        }
        val required = requiredBasisFields(text)
        val missing = required.filterTo(linkedSetOf()) { field ->
            when (field) {
                NoticeBasisField.REFERENCE_DATE -> basis.dates.referenceDate == null
                NoticeBasisField.TERM_MONDAY -> basis.dates.termMonday == null
                NoticeBasisField.TOTAL_WEEKS -> basis.dates.totalWeeks == null
                NoticeBasisField.YEAR -> basis.dates.confirmedYear == null && basis.dates.termMonday == null
            }
        }
        if (missing.isNotEmpty()) return listOf(NoticeIntentResult.NeedBasis(missing))

        // The sentinel is reachable only when the text has no relative/month-day token (absolute dates
// and explicit week numbers carry their own basis), so it can never resolve a real date here.
        val reference = basis.dates.referenceDate ?: basis.dates.termMonday ?: LocalDate.of(1970, 1, 1)
        val weeks = basis.dates.totalWeeks ?: maxWeek ?: 30
        val context = NoticeParseContext(
            FormatParseContext("notice-intent", weeks.coerceIn(1, 1000), limits, cancellation),
            reference,
            basis.dates.termMonday,
            basis.dates.confirmedYear,
        )
        val parsed = NoticeTimetableParser().parse(text, context)
        cancellation.check()
        val draft = parsed.draft
        if (draft == null) {
            return listOf(NoticeIntentResult.Blocked(
                parsed.issues.map { NoticeProblem(it.code, it.message) }.ifEmpty { listOf(NoticeProblem(NoticeCodes.UNSUPPORTED_NOTICE)) }
            ))
        }
        if (parsed.pendingExceptions.isEmpty()) {
            val ambiguous = parsed.issues.any { it.code.startsWith("AMBIGUOUS_") }
            return listOf(NoticeIntentResult.Blocked(listOf(
                NoticeProblem(if (ambiguous) NoticeCodes.AMBIGUOUS_NOTICE else NoticeCodes.UNSUPPORTED_NOTICE)
            )))
        }
        val results = parsed.pendingExceptions.map { pending ->
            cancellation.check()
            intentFor(pending, text, basis, parsed.issues.map { it.code }, parsed.relativeDates)
        }
        return results
    }

    private fun intentFor(
        pending: PendingExceptionAttachment,
        text: String,
        basis: NoticeBasis,
        issueCodes: List<String>,
        relativeRules: List<com.lingion.sleepy.importing.formats.RelativeDateProposal>,
    ): NoticeIntentResult {
        val exception = pending.exception
        val roles = pending.roles
        var kind = roles?.intent ?: when (exception.kind) {
            RichExceptionKind.CANCEL -> NoticeIntentKind.CANCEL
            RichExceptionKind.EXTRA -> NoticeIntentKind.EXTRA
            RichExceptionKind.RESCHEDULE -> if (exception.targetDate != null && exception.targetDate != exception.originalDate)
                NoticeIntentKind.CHANGE_DATE else NoticeIntentKind.RESCHEDULE_TIME
        }
        var original = roles?.original ?: when (exception.kind) {
            RichExceptionKind.CANCEL -> NoticeScope(exception.sections.orEmpty(), exception.customTime)
            else -> NoticeScope()
        }
        var target = roles?.target ?: when (exception.kind) {
            RichExceptionKind.CANCEL -> NoticeScope()
            else -> NoticeScope(exception.sections.orEmpty(), exception.customTime)
        }
        // 上午/下午第N大节 needs the user-confirmed mapping; it is never two guessed periods.
        val bigSection = roles?.bigSection
        if (bigSection != null) {
            val mapped = basis.bigSections?.sections(bigSection.part, bigSection.ordinal)
                ?: return NoticeIntentResult.NeedTime(bigSection, NoticeCodes.NEED_PERIOD_MAPPING)
            if (mapped.isEmpty()) return NoticeIntentResult.NeedTime(bigSection, NoticeCodes.NEED_PERIOD_MAPPING)
            if (bigSection.originalSide) original = original.copy(sections = original.sections + mapped)
            else target = target.copy(sections = target.sections + mapped)
        }
        val bareDayPart = issueCodes.contains("UNRESOLVED_TIME_OF_DAY") && bigSection == null && dayPartTokens.containsMatchIn(text)
        if (bareDayPart && kind != NoticeIntentKind.CANCEL && target.time == null && target.sections.isEmpty() &&
            kind != NoticeIntentKind.ROOM_ONLY) {
            return NoticeIntentResult.NeedTime(null, NoticeCodes.NEED_TIME)
        }
        if (kind == NoticeIntentKind.EXTRA && target.sections.isEmpty() && target.time == null) {
            return NoticeIntentResult.NeedTime(null, NoticeCodes.NEED_TIME)
        }
        if (kind != NoticeIntentKind.EXTRA && exception.originalDate == null) {
            return NoticeIntentResult.Blocked(listOf(NoticeProblem(NoticeCodes.NEED_ORIGINAL_DATE)))
        }
        if (kind == NoticeIntentKind.CHANGE_DATE && exception.targetDate == null) {
            return NoticeIntentResult.Blocked(listOf(NoticeProblem(NoticeCodes.NEED_TARGET_DATE)))
        }
        if (kind == NoticeIntentKind.EXTRA && exception.targetDate == null) {
            return NoticeIntentResult.Blocked(listOf(NoticeProblem(NoticeCodes.NEED_TARGET_DATE)))
        }
        val targetDate = exception.targetDate ?: if (kind == NoticeIntentKind.RESCHEDULE_TIME || kind == NoticeIntentKind.ROOM_ONLY)
            exception.originalDate else null
        val source = pending.sources.firstOrNull()
        val rule = relativeRules.firstOrNull { proposal ->
            val value = proposal.candidate
            value != null && (value == exception.originalDate || value == targetDate)
        }?.ruleId
        val codeProblems = issueCodes.distinct().map { NoticeProblem(it) }
        val invalidProblems = buildList {
            if (roles?.originalTimeInvalid == true) add(NoticeProblem(NoticeCodes.INVALID_ORIGINAL_TIME))
            if (roles?.originalSectionsInvalid == true) add(NoticeProblem(NoticeCodes.INVALID_ORIGINAL_SECTIONS))
            if (roles?.targetTimeInvalid == true) add(NoticeProblem(NoticeCodes.INVALID_TARGET_TIME))
            if (roles?.targetSectionsInvalid == true) add(NoticeProblem(NoticeCodes.INVALID_TARGET_SECTIONS))
        }
        if (codeProblems.any { it.code.startsWith("AMBIGUOUS_") }) {
            return NoticeIntentResult.Blocked(codeProblems)
        }
        val intent = NoticeIntent(
                kind = kind,
                rawText = source?.rawText ?: text,
                clauses = pending.sources.map { NoticeClause(it.sourceId, it.sourceLocator, it.rawText) }
                    .ifEmpty { listOf(NoticeClause(null, null, text)) },
                basis = basis.dates,
                courseNameHint = pending.courseName?.takeIf { it.isNotBlank() },
                teacher = pending.teacher,
                room = pending.room,
                group = pending.group,
                project = pending.experimentProject,
                originalDate = exception.originalDate,
                targetDate = targetDate,
                original = original,
                target = target,
                roomChange = roles?.room,
                bigSection = bigSection,
                declaredWeeks = pending.declaredWeeks,
                declaredDayOfWeek = pending.dayOfWeek,
                dateRule = rule,
                sameDayAdjustment = roles?.sameDayAdjustment == true ||
                    (kind == NoticeIntentKind.RESCHEDULE_TIME && exception.originalDate != null && exception.originalDate == targetDate),
                invalidOriginalTime = roles?.originalTimeInvalid == true,
                invalidOriginalSections = roles?.originalSectionsInvalid == true,
                invalidTargetTime = roles?.targetTimeInvalid == true,
                invalidTargetSections = roles?.targetSectionsInvalid == true,
                warnings = codeProblems,
        )
        if (intent.requiresCorrection || invalidProblems.isNotEmpty()) {
            // Malformed explicit roles stay blocking until the named field is corrected.
            return NoticeIntentResult.CorrectionRequired(intent, invalidProblems)
        }
        return NoticeIntentResult.Ready(intent)
    }
}