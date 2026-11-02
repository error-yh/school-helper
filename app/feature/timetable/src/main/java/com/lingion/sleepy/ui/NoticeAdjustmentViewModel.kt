/* SPDX-License-Identifier: GPL-3.0-only
 * UI state machine for single-occurrence notice adjustments and operation history.
 * Preview is read-only; writes happen only on explicit user confirmation via apply/undo.
 */
package com.lingion.sleepy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.ImportCanonical
import com.lingion.sleepy.domain.imports.ImportLogicalBaseDisplay
import com.lingion.sleepy.domain.imports.ImportTarget
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.notice.NoticeAdjustmentApplyResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentCodes
import com.lingion.sleepy.domain.notice.NoticeAdjustmentConfirmation
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistory
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryEntry
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreview
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreviewResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentProposal
import com.lingion.sleepy.domain.notice.NoticeAdjustmentRequest
import com.lingion.sleepy.domain.notice.NoticeAdjustmentUndoResult
import com.lingion.sleepy.domain.notice.NoticeBasis
import com.lingion.sleepy.domain.notice.NoticeBasisField
import com.lingion.sleepy.domain.notice.NoticeCandidate
import com.lingion.sleepy.domain.notice.NoticeCandidateId
import com.lingion.sleepy.domain.notice.NoticeCandidateResult
import com.lingion.sleepy.domain.notice.NoticeCodes
import com.lingion.sleepy.domain.notice.NoticeConflict
import com.lingion.sleepy.domain.notice.NoticeCorrections
import com.lingion.sleepy.domain.notice.NoticeCourseSelection
import com.lingion.sleepy.domain.notice.NoticeIntent
import com.lingion.sleepy.domain.notice.NoticeIntentParser
import com.lingion.sleepy.domain.notice.NoticeIntentResult
import com.lingion.sleepy.domain.notice.NoticeOccurrenceResolver
import com.lingion.sleepy.domain.notice.NoticeProblem
import com.lingion.sleepy.domain.repository.ImportRepository
import com.lingion.sleepy.domain.repository.NoticeAdjustmentRepository
import com.lingion.sleepy.domain.repository.ScheduleRepository
import com.lingion.sleepy.importing.DraftTimeRange
import com.lingion.sleepy.importing.NoticeBigSection
import com.lingion.sleepy.importing.NoticeBigSectionMapping
import com.lingion.sleepy.importing.NoticeDateBasis
import com.lingion.sleepy.importing.NoticeDayPart
import com.lingion.sleepy.importing.NoticeIntentKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun formatNoticeCode(code: String, detail: String? = null): String {
    val base = when (code) {
        NoticeAdjustmentCodes.STALE_TARGET -> "课表目标或学期已变更，请刷新后重试"
        NoticeAdjustmentCodes.STALE_CONFIRMATION -> "预览确认已失效，请重新生成预览"
        NoticeAdjustmentCodes.STALE_REVISION -> "课表数据已更新，请重新生成预览后再确认"
        NoticeAdjustmentCodes.CONFLICT_PENDING -> "存在未确认的时间冲突，需确认后方可继续"
        NoticeAdjustmentCodes.ALREADY_APPLIED -> "该通知调整此前已生效，无需重复应用"
        NoticeAdjustmentCodes.STALE_EVENT_EFFECT -> "历史调整效果已失效或已被覆盖，需重新核对课表"
        NoticeAdjustmentCodes.NO_COURSE -> "补课必须选择已有的对应课程"
        NoticeAdjustmentCodes.COURSE_MISSING -> "对应课程在课表中已不存在"
        NoticeAdjustmentCodes.MEETING_MISSING -> "对应课时安排已不存在"
        NoticeAdjustmentCodes.REPLACE_UNSUPPORTED_FOR_NORMALIZED -> "该课程由外部导入（归一化课），暂不支持在此基础上直接替换已有调课。如需调整，请先撤销旧调整或重新导入课表"
        NoticeAdjustmentCodes.REPLACED_ROW_MISSING -> "要替换的既有调整记录已不存在"
        NoticeAdjustmentCodes.PREVIEW_FAILED -> "生成调整预览失败，请检查输入"
        NoticeAdjustmentCodes.APPLY_FAILED -> "应用调课失败，数据库未作修改"
        NoticeAdjustmentCodes.WRITE_CONFLICT -> "数据库写入冲突，请重试"
        NoticeAdjustmentCodes.INVALID_EVENT_PAYLOAD -> "调整事件数据异常"
        NoticeAdjustmentCodes.EVENT_NOT_FOUND -> "未找到对应的调整历史记录"
        NoticeAdjustmentCodes.EVENT_NOT_APPLIED -> "该调整记录当前未处于生效状态"
        NoticeAdjustmentCodes.UNDO_TARGET_MISSING -> "撤销失败：原课程或课时安排已不存在"
        NoticeAdjustmentCodes.UNDO_AFTER_MISMATCH -> "撤销失败：当前课程安排已被后续操作修改"
        NoticeAdjustmentCodes.UNDO_OWNERSHIP_CHANGED -> "撤销失败：课时归属已变更"
        NoticeAdjustmentCodes.UNDO_STATE_MISSING -> "撤销失败：所需历史状态丢失"
        NoticeAdjustmentCodes.UNDO_INCONSISTENT -> "撤销失败：数据不一致"
        NoticeAdjustmentCodes.LATER_EVENT_DEPENDS -> "撤销失败：存在依赖此调整的后续操作，无法直接撤销"
        NoticeAdjustmentCodes.UNDO_FAILED -> "撤销操作未成功，课表未更改"
        NoticeAdjustmentCodes.CORRUPT_EVENT -> "调整记录数据损坏"
        NoticeAdjustmentCodes.INVALID_LIMIT -> "分页查询参数无效"
        NoticeCodes.NEED_REFERENCE_DATE -> "请指定通知发布日期或相对基准日"
        NoticeCodes.NEED_TERM_MONDAY -> "请指定学期开学第一周周一日期"
        NoticeCodes.NEED_TOTAL_WEEKS -> "请指定学期总周数"
        NoticeCodes.NEED_YEAR -> "请指定年份"
        NoticeCodes.NEED_TIME -> "通知中仅提到模糊时段，请输入具体时间或节次"
        NoticeCodes.NEED_PERIOD_MAPPING -> "通知中提到大节，请指定大节对应的起止节次"
        NoticeCodes.NEED_ORIGINAL_DATE -> "无法识别原课程日期，请指定或纠正原日期"
        NoticeCodes.NEED_TARGET_DATE -> "无法识别调整后目标日期，请指定或纠正新日期"
        NoticeCodes.UNSUPPORTED_NOTICE -> "暂不支持此种类型的调课通知"
        NoticeCodes.AMBIGUOUS_NOTICE -> "通知内容存在歧义，无法准确解析"
        NoticeCodes.LIMIT_EXCEEDED -> "通知内容过长，超出限制"
        NoticeCodes.DATE_OUTSIDE_TERM -> "涉及的日期不在当前学期教学周范围内"
        NoticeCodes.OCCURRENCE_NOT_FOUND -> "未在课表中找到匹配的原课程课时"
        NoticeCodes.OCCURRENCE_ALREADY_CANCELLED -> "该课时此前已被取消"
        NoticeCodes.OLD_SCOPE_MISMATCH -> "原课时时间或节次与当前课表不匹配"
        NoticeCodes.NAME_HINT_MISMATCH -> "课程名称提示与找到的课时不匹配"
        NoticeCodes.NEED_MANUAL_SELECTION -> "找到多个候选课时或需手动确认课时（唯一候选也须明确点击选择以确认）"
        NoticeCodes.NEED_COURSE_SELECTION -> "补课必须选择已有的对应课程"
        NoticeCodes.STALE_CANDIDATE -> "所选课时候选已失效，请重新选择"
        NoticeCodes.CORRECTION_REQUIRED -> "通知中存在格式异常的时间或节次，需手动纠正后重新生成"
        NoticeCodes.INVALID_ORIGINAL_TIME -> "原课程时间格式无效"
        NoticeCodes.INVALID_ORIGINAL_SECTIONS -> "原课程节次格式无效"
        NoticeCodes.INVALID_TARGET_TIME -> "调整后目标时间格式无效"
        NoticeCodes.INVALID_TARGET_SECTIONS -> "调整后目标节次格式无效"
        NoticeCodes.AMBIGUOUS_OCCURRENCE -> "存在多个相同的课时，无法唯一确定"
        NoticeCodes.ALREADY_ADJUSTED -> "该节课当天已存在调课记录；继续操作将替换已有调整"
        NoticeCodes.CALENDAR_MISMATCH -> "学期基准或范围与当前课表不匹配"
        NoticeCodes.TARGET_PERIOD_MISSING -> "缺少调整后目标节次或时间"
        NoticeCodes.NO_EFFECTIVE_CHANGE -> "调整后安排与原安排完全相同，无实际变动"
        else -> code
    }
    return if (detail.isNullOrBlank()) base else "$base ($detail)"
}

data class NoticeAdjustmentUiState(
    val rawText: String = "",
    // 日期依据输入
    val referenceDateInput: String = "",
    val termMondayInput: String = "",
    val totalWeeksInput: String = "",
    val yearInput: String = "",
    // 大节映射输入
    val bigSectionStartInput: String = "",
    val bigSectionEndInput: String = "",
    val bigSectionOrdinalInputs: Map<Int, Pair<String, String>> = emptyMap(),
    val bigSectionMappingError: String? = null,
    // 纠正输入 - 原安排
    val correctionOriginalSections: String = "",
    val correctionOriginalTimeStart: String = "",
    val correctionOriginalTimeEnd: String = "",
    // 纠正输入 - 调整后安排
    val correctionTargetDate: String = "",
    val correctionTargetSections: String = "",
    val correctionTargetTimeStart: String = "",
    val correctionTargetTimeEnd: String = "",
    val correctionRoom: String = "",
    val correctionRoomKeep: Boolean = false,
    val correctionError: String? = null,
    // 解析结果
    val isParsing: Boolean = false,
    val parseResults: List<NoticeIntentResult> = emptyList(),
    val selectedIntentIndex: Int = 0,
    val parseProblems: List<NoticeProblem> = emptyList(),
    val missingBasisFields: Set<NoticeBasisField> = emptySet(),
    val needTimeBigSection: NoticeBigSection? = null,
    // 候选与补课课程
    val candidates: List<NoticeCandidate> = emptyList(),
    val selectedCandidateId: NoticeCandidateId? = null,
    val availableCourses: List<ScheduledCourse> = emptyList(),
    val selectedExtraCourseId: Long? = null,
    val replaceExistingAdjustment: Boolean = false,
    val alreadyAdjustedCandidate: Boolean = false,
    // 冲突
    val detectedConflicts: List<NoticeConflict> = emptyList(),
    val acknowledgedConflictFingerprints: Set<String> = emptySet(),
    // 预览与确认
    val isPreviewing: Boolean = false,
    val preview: NoticeAdjustmentPreview? = null,
    val previewProblems: List<NoticeProblem> = emptyList(),
    val isAlreadyApplied: Boolean = false,
    val alreadyAppliedEventId: Long? = null,
    // 执行与结果反馈
    val isApplying: Boolean = false,
    val appliedSuccessMessage: String? = null,
    val appliedEventId: Long? = null,
    val applyErrorMessage: String? = null,
    val generalErrorMessage: String? = null,
)

class NoticeAdjustmentViewModel(
    private val noticeRepository: NoticeAdjustmentRepository,
    private val scheduleRepository: ScheduleRepository,
    private val importRepository: ImportRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoticeAdjustmentUiState())
    val uiState: StateFlow<NoticeAdjustmentUiState> = _uiState.asStateFlow()

    private var currentGeneration = 0L
    private var previewJob: Job? = null
    private var candidateJob: Job? = null
    private var applyJob: Job? = null

    class Factory(private val dependencies: TimetableDependencies) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val noticeRepo = checkNotNull(dependencies.noticeAdjustments) {
                "NoticeAdjustmentRepository is required"
            }
            return NoticeAdjustmentViewModel(
                noticeRepository = noticeRepo,
                scheduleRepository = dependencies.repository,
                importRepository = dependencies.imports,
            ) as T
        }
    }

    private fun nextGeneration(): Long {
        currentGeneration++
        previewJob?.cancel()
        previewJob = null
        candidateJob?.cancel()
        candidateJob = null
        _uiState.update { it.copy(isPreviewing = false) }
        return currentGeneration
    }

    private fun clearConfirmationAndResults() {
        _uiState.update {
            it.copy(
                preview = null,
                previewProblems = emptyList(),
                isAlreadyApplied = false,
                alreadyAppliedEventId = null,
                appliedSuccessMessage = null,
                appliedEventId = null,
                applyErrorMessage = null,
                generalErrorMessage = null,
            )
        }
    }

    private fun clearDerivedParseAndCandidates() {
        _uiState.update {
            it.copy(
                parseResults = emptyList(),
                selectedIntentIndex = 0,
                parseProblems = emptyList(),
                missingBasisFields = emptySet(),
                needTimeBigSection = null,
                candidates = emptyList(),
                selectedCandidateId = null,
                availableCourses = emptyList(),
                selectedExtraCourseId = null,
                replaceExistingAdjustment = false,
                alreadyAdjustedCandidate = false,
                detectedConflicts = emptyList(),
                acknowledgedConflictFingerprints = emptySet(),
                correctionOriginalSections = "",
                correctionOriginalTimeStart = "",
                correctionOriginalTimeEnd = "",
                correctionTargetDate = "",
                correctionTargetSections = "",
                correctionTargetTimeStart = "",
                correctionTargetTimeEnd = "",
                correctionRoom = "",
                correctionRoomKeep = false,
                correctionError = null,
                bigSectionStartInput = "",
                bigSectionEndInput = "",
                bigSectionOrdinalInputs = emptyMap(),
                bigSectionMappingError = null,
            )
        }
        clearConfirmationAndResults()
    }

    fun updateRawText(text: String) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearDerivedParseAndCandidates()
        _uiState.update { it.copy(rawText = text) }
    }

    fun updateReferenceDateInput(input: String) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearDerivedParseAndCandidates()
        _uiState.update { it.copy(referenceDateInput = input) }
    }

    fun useTodayAsReferenceDate() {
        if (_uiState.value.isApplying) return
        val today = LocalDate.now().toString()
        updateReferenceDateInput(today)
    }

    fun updateTermMondayInput(input: String) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearDerivedParseAndCandidates()
        _uiState.update { it.copy(termMondayInput = input) }
    }

    fun updateTotalWeeksInput(input: String) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearDerivedParseAndCandidates()
        _uiState.update { it.copy(totalWeeksInput = input) }
    }

    fun updateYearInput(input: String) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearDerivedParseAndCandidates()
        _uiState.update { it.copy(yearInput = input) }
    }

    fun useCurrentSemesterBasis(termMonday: LocalDate?, totalWeeks: Int?) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearDerivedParseAndCandidates()
        _uiState.update {
            it.copy(
                termMondayInput = termMonday?.toString() ?: it.termMondayInput,
                totalWeeksInput = totalWeeks?.toString() ?: it.totalWeeksInput,
            )
        }
    }

    fun updateBigSectionMapping(
        start: String,
        end: String,
        priorMappings: Map<Int, Pair<String, String>> = emptyMap(),
    ) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        _uiState.update { state ->
            val targetOrdinal = state.needTimeBigSection?.ordinal ?: 1
            val updated = state.bigSectionOrdinalInputs.toMutableMap()
            updated.putAll(priorMappings)
            updated[targetOrdinal] = Pair(start, end)
            state.copy(
                bigSectionStartInput = start,
                bigSectionEndInput = end,
                bigSectionOrdinalInputs = updated,
                bigSectionMappingError = null,
            )
        }
    }

    fun updateBigSectionMappingForOrdinal(ordinal: Int, start: String, end: String) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        _uiState.update { state ->
            val updated = state.bigSectionOrdinalInputs + (ordinal to Pair(start, end))
            val isCurrentTarget = (state.needTimeBigSection?.ordinal ?: 1) == ordinal
            state.copy(
                bigSectionStartInput = if (isCurrentTarget) start else state.bigSectionStartInput,
                bigSectionEndInput = if (isCurrentTarget) end else state.bigSectionEndInput,
                bigSectionOrdinalInputs = updated,
                bigSectionMappingError = null,
            )
        }
    }

    fun updateCorrectionOriginalSections(input: String) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        _uiState.update { it.copy(correctionOriginalSections = input, correctionError = null) }
    }

    fun updateCorrectionOriginalTime(start: String, end: String) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        _uiState.update {
            it.copy(correctionOriginalTimeStart = start, correctionOriginalTimeEnd = end, correctionError = null)
        }
    }

    fun updateCorrectionTargetDate(input: String) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        _uiState.update { it.copy(correctionTargetDate = input, correctionError = null) }
    }

    fun updateCorrectionTargetSections(input: String) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        _uiState.update { it.copy(correctionTargetSections = input, correctionError = null) }
    }

    fun updateCorrectionTargetTime(start: String, end: String) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        _uiState.update {
            it.copy(correctionTargetTimeStart = start, correctionTargetTimeEnd = end, correctionError = null)
        }
    }

    fun updateCorrectionRoom(room: String) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        _uiState.update { it.copy(correctionRoom = room, correctionRoomKeep = false, correctionError = null) }
    }

    fun setCorrectionRoomKeep(keep: Boolean) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        _uiState.update {
            it.copy(
                correctionRoomKeep = keep,
                correctionRoom = if (keep) "" else it.correctionRoom,
                correctionError = null
            )
        }
    }

    fun selectIntentIndex(index: Int) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        _uiState.update {
            it.copy(
                selectedIntentIndex = index,
                candidates = emptyList(),
                selectedCandidateId = null,
                selectedExtraCourseId = null,
                replaceExistingAdjustment = false,
                alreadyAdjustedCandidate = false,
                detectedConflicts = emptyList(),
                acknowledgedConflictFingerprints = emptySet(),
                correctionOriginalSections = "",
                correctionOriginalTimeStart = "",
                correctionOriginalTimeEnd = "",
                correctionTargetDate = "",
                correctionTargetSections = "",
                correctionTargetTimeStart = "",
                correctionTargetTimeEnd = "",
                correctionRoom = "",
                correctionRoomKeep = false,
                correctionError = null,
                bigSectionStartInput = "",
                bigSectionEndInput = "",
                bigSectionOrdinalInputs = emptyMap(),
                bigSectionMappingError = null,
            )
        }
        candidateJob = viewModelScope.launch {
            loadCandidatesOrCourses()
        }
    }

    private fun parseSectionsStrict(raw: String): Result<Set<Int>?> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.success(null)
        val tokens = trimmed.split(',', '，', '、', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Result.success(null)

        val result = mutableSetOf<Int>()
        for (token in tokens) {
            val rangeParts = token.split('-', '–', '~', '至')
            if (rangeParts.size == 2) {
                val start = rangeParts[0].trim().toIntOrNull()
                val end = rangeParts[1].trim().toIntOrNull()
                if (start == null || end == null || start <= 0 || end < start || end > 30) {
                    return Result.failure(IllegalArgumentException("节次范围无效：'$token'，必须是1-30之间的连续节次（如 5-8）"))
                }
                for (s in start..end) result.add(s)
            } else if (rangeParts.size == 1) {
                val sec = rangeParts[0].trim().toIntOrNull()
                if (sec == null || sec <= 0 || sec > 30) {
                    return Result.failure(IllegalArgumentException("无效节次：'$token'，必须是1-30之间的整数"))
                }
                result.add(sec)
            } else {
                return Result.failure(IllegalArgumentException("无法识别节次表达：'$token'"))
            }
        }
        return Result.success(result)
    }

    private fun parseTimeRangeStrict(startStr: String, endStr: String): Result<DraftTimeRange?> {
        val s = startStr.trim()
        val e = endStr.trim()
        if (s.isEmpty() && e.isEmpty()) return Result.success(null)
        if (s.isEmpty() || e.isEmpty()) {
            return Result.failure(IllegalArgumentException("时间段必须同时填写起始时间与结束时间"))
        }
        val startTime = try {
            LocalTime.parse(s)
        } catch (_: DateTimeParseException) {
            return Result.failure(IllegalArgumentException("起始时间格式无效（须为HH:mm，如 14:00）: '$s'"))
        }
        val endTime = try {
            LocalTime.parse(e)
        } catch (_: DateTimeParseException) {
            return Result.failure(IllegalArgumentException("结束时间格式无效（须为HH:mm，如 15:30）: '$e'"))
        }
        if (!startTime.isBefore(endTime)) {
            return Result.failure(IllegalArgumentException("起始时间 ($s) 必须早于结束时间 ($e)"))
        }
        return Result.success(DraftTimeRange(startTime, endTime))
    }

    private fun parseDateStrict(dateStr: String): Result<LocalDate?> {
        val d = dateStr.trim()
        if (d.isEmpty()) return Result.success(null)
        return try {
            Result.success(LocalDate.parse(d))
        } catch (_: DateTimeParseException) {
            Result.failure(IllegalArgumentException("日期格式无效（须为年-月-日，如 2026-11-13）: '$d'"))
        }
    }

    private fun buildDateBasisOnly(): Result<NoticeDateBasis> {
        val s = _uiState.value
        val refDate = if (s.referenceDateInput.isNotBlank()) {
            parseDateStrict(s.referenceDateInput).getOrElse { return Result.failure(it) }
        } else null

        val termMon = if (s.termMondayInput.isNotBlank()) {
            val d = parseDateStrict(s.termMondayInput).getOrElse { return Result.failure(it) }
            if (d != null && d.dayOfWeek.value != 1) {
                return Result.failure(IllegalArgumentException("开学第一周周一必须是星期一"))
            }
            d
        } else null

        val weeks = if (s.totalWeeksInput.isNotBlank()) {
            val w = s.totalWeeksInput.trim().toIntOrNull()
            if (w == null || w <= 0 || w > 40) {
                return Result.failure(IllegalArgumentException("总周数无效：须为1-40之间的正整数"))
            }
            w
        } else null

        val year = if (s.yearInput.isNotBlank()) {
            val y = s.yearInput.trim().toIntOrNull()
            if (y == null || y !in 1900..2100) {
                return Result.failure(IllegalArgumentException("年份无效：须为有效年份"))
            }
            y
        } else null

        return Result.success(
            NoticeDateBasis(
                referenceDate = refDate,
                termMonday = termMon,
                totalWeeks = weeks,
                confirmedYear = year,
            )
        )
    }

    private fun detectRequiredBigSection(text: String, dateBasis: NoticeDateBasis, selectedIndex: Int): NoticeBigSection? {
        val probeResults = runCatching {
            NoticeIntentParser.parse(text, NoticeBasis(dates = dateBasis, bigSections = null))
        }.getOrNull() ?: return null
        if (selectedIndex in probeResults.indices) {
            val res = probeResults[selectedIndex]
            if (res is NoticeIntentResult.NeedTime && res.bigSection != null) {
                return res.bigSection
            }
        }
        return probeResults.filterIsInstance<NoticeIntentResult.NeedTime>().firstOrNull()?.bigSection
    }

    private fun buildNoticeBasis(textOverride: String? = null): Result<NoticeBasis> {
        val s = _uiState.value
        val dateBasis = buildDateBasisOnly().getOrElse { return Result.failure(it) }

        val rawNoticeText = textOverride ?: s.rawText.trim()
        val detectedBigSec = detectRequiredBigSection(rawNoticeText, dateBasis, s.selectedIntentIndex)
            ?: s.needTimeBigSection

        val hasMappingInput = s.bigSectionStartInput.isNotBlank() ||
            s.bigSectionEndInput.isNotBlank() ||
            s.bigSectionOrdinalInputs.isNotEmpty()

        if (!hasMappingInput) {
            return Result.success(NoticeBasis(dateBasis, null))
        }

        if (detectedBigSec == null) {
            return Result.success(NoticeBasis(dateBasis, null))
        }

        val part = detectedBigSec.part
        val targetOrdinal = detectedBigSec.ordinal
        val inputs = s.bigSectionOrdinalInputs.toMutableMap()
        if (s.bigSectionStartInput.isNotBlank() || s.bigSectionEndInput.isNotBlank()) {
            inputs.putIfAbsent(targetOrdinal, Pair(s.bigSectionStartInput, s.bigSectionEndInput))
        }

        // 校验完整性：从 1 到 targetOrdinal 必须全部提供
        val missingOrdinals = (1..targetOrdinal).filter { ord ->
            val p = inputs[ord]
            p == null || p.first.isBlank() || p.second.isBlank()
        }
        if (missingOrdinals.isNotEmpty()) {
            val missingMsg = missingOrdinals.joinToString("、") { "第${it}大节" }
            return Result.failure(IllegalArgumentException("大节映射信息不完整：需要补充提供 $missingMsg 对应的节次"))
        }

        // 展开前有界校验：限制 1..30，顺序 start <= end，跨度 <= 12
        val groups = mutableListOf<Set<Int>>()
        for (ord in 1..targetOrdinal) {
            val (startStr, endStr) = inputs[ord] ?: return Result.failure(IllegalArgumentException("缺少第${ord}大节映射"))
            val start = startStr.trim().toIntOrNull()
            val end = endStr.trim().toIntOrNull()
            if (start == null || end == null) {
                return Result.failure(IllegalArgumentException("第${ord}大节节次无效：必须为纯数字"))
            }
            if (start <= 0 || start > 30 || end <= 0 || end > 30) {
                return Result.failure(IllegalArgumentException("第${ord}大节节次超出允许范围：节次必须在 1 到 30 之间"))
            }
            if (end < start) {
                return Result.failure(IllegalArgumentException("第${ord}大节节次顺序无效：起始节 ($start) 不能大于结束节 ($end)"))
            }
            if (end - start + 1 > 12) {
                return Result.failure(IllegalArgumentException("第${ord}大节节次跨度过大：单次大节跨度不得超过 12 节"))
            }
            groups.add((start..end).toSet())
        }

        // 校验重复与重叠
        val flattened = groups.flatten()
        if (flattened.size != flattened.toSet().size) {
            return Result.failure(IllegalArgumentException("大节映射不能重复节次：不同大节之间存在重叠节次"))
        }

        val mapping = try {
            if (part == NoticeDayPart.MORNING) {
                NoticeBigSectionMapping(morning = groups)
            } else {
                NoticeBigSectionMapping(afternoon = groups)
            }
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("大节映射构造失败: ${e.message}"))
        }

        return Result.success(NoticeBasis(dateBasis, mapping))
    }

    fun parse() {
        if (_uiState.value.isApplying) return
        val gen = nextGeneration()
        clearConfirmationAndResults()
        val text = _uiState.value.rawText.trim()
        if (text.isEmpty()) {
            _uiState.update {
                it.copy(generalErrorMessage = "请输入通知内容")
            }
            return
        }

        // 预探测真实大节语义身份并保留
        val preDateBasis = buildDateBasisOnly().getOrNull()
        if (preDateBasis != null) {
            val detected = detectRequiredBigSection(text, preDateBasis, _uiState.value.selectedIntentIndex)
            if (detected != null) {
                _uiState.update { it.copy(needTimeBigSection = detected) }
            }
        }

        val basisResult = buildNoticeBasis(text)
        if (basisResult.isFailure) {
            val errorMsg = basisResult.exceptionOrNull()?.message
            _uiState.update {
                it.copy(
                    generalErrorMessage = errorMsg,
                    bigSectionMappingError = errorMsg,
                    parseResults = emptyList(),
                    candidates = emptyList(),
                )
            }
            return
        }
        val basis = basisResult.getOrThrow()

        val required = NoticeIntentParser.requiredBasisFields(text)
        val missing = required.filterTo(linkedSetOf()) { field ->
            when (field) {
                NoticeBasisField.REFERENCE_DATE -> basis.dates.referenceDate == null
                NoticeBasisField.TERM_MONDAY -> basis.dates.termMonday == null
                NoticeBasisField.TOTAL_WEEKS -> basis.dates.totalWeeks == null
                NoticeBasisField.YEAR -> basis.dates.confirmedYear == null && basis.dates.termMonday == null
            }
        }

        if (missing.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    missingBasisFields = missing,
                    parseProblems = listOf(NoticeProblem("NEED_BASIS", "缺少日期依据，请在下方补充确认")),
                    parseResults = emptyList(),
                    candidates = emptyList(),
                    selectedCandidateId = null,
                )
            }
            return
        }

        _uiState.update { it.copy(isParsing = true) }
        val results = NoticeIntentParser.parse(text, basis)
        if (gen != currentGeneration) return

        val problems = mutableListOf<NoticeProblem>()
        var needTimeSection: NoticeBigSection? = null

        results.forEach { result ->
            when (result) {
                is NoticeIntentResult.NeedBasis -> {
                    problems.add(NoticeProblem("NEED_BASIS", "缺少日期依据"))
                }
                is NoticeIntentResult.NeedTime -> {
                    needTimeSection = result.bigSection
                    problems.add(NoticeProblem(result.code, "需要明确的时间段或节次映射"))
                }
                is NoticeIntentResult.CorrectionRequired -> {
                    problems.addAll(result.problems)
                }
                is NoticeIntentResult.Blocked -> {
                    problems.addAll(result.problems)
                }
                is NoticeIntentResult.Ready -> Unit
            }
        }

        _uiState.update {
            it.copy(
                isParsing = false,
                parseResults = results,
                selectedIntentIndex = 0,
                parseProblems = problems,
                missingBasisFields = emptySet(),
                needTimeBigSection = needTimeSection ?: it.needTimeBigSection,
                bigSectionMappingError = null,
                candidates = emptyList(),
                selectedCandidateId = null,
                selectedExtraCourseId = null,
            )
        }

        candidateJob = viewModelScope.launch {
            loadCandidatesOrCourses()
        }
    }

    private fun buildCorrections(): Result<NoticeCorrections> {
        val s = _uiState.value

        val origSectionsRes = parseSectionsStrict(s.correctionOriginalSections)
        if (origSectionsRes.isFailure) return Result.failure(origSectionsRes.exceptionOrNull()!!)
        val originalSections = origSectionsRes.getOrNull()

        val origTimeRes = parseTimeRangeStrict(s.correctionOriginalTimeStart, s.correctionOriginalTimeEnd)
        if (origTimeRes.isFailure) return Result.failure(origTimeRes.exceptionOrNull()!!)
        val originalTime = origTimeRes.getOrNull()

        val targetSectionsRes = parseSectionsStrict(s.correctionTargetSections)
        if (targetSectionsRes.isFailure) return Result.failure(targetSectionsRes.exceptionOrNull()!!)
        val targetSections = targetSectionsRes.getOrNull()

        val targetTimeRes = parseTimeRangeStrict(s.correctionTargetTimeStart, s.correctionTargetTimeEnd)
        if (targetTimeRes.isFailure) return Result.failure(targetTimeRes.exceptionOrNull()!!)
        val targetTime = targetTimeRes.getOrNull()

        val targetDateRes = parseDateStrict(s.correctionTargetDate)
        if (targetDateRes.isFailure) return Result.failure(targetDateRes.exceptionOrNull()!!)
        val targetDate = targetDateRes.getOrNull()

        val room = s.correctionRoom.trim().takeIf { it.isNotEmpty() }
        val roomKeep = s.correctionRoomKeep

        return Result.success(
            NoticeCorrections(
                originalSections = originalSections,
                originalTime = originalTime,
                targetSections = targetSections,
                targetTime = targetTime,
                targetDate = targetDate,
                room = room,
                roomKeep = roomKeep,
            )
        )
    }

    private fun currentActiveIntent(): NoticeIntent? {
        val s = _uiState.value
        if (s.parseResults.isEmpty() || s.selectedIntentIndex !in s.parseResults.indices) return null
        val intent = when (val res = s.parseResults[s.selectedIntentIndex]) {
            is NoticeIntentResult.Ready -> res.intent
            is NoticeIntentResult.CorrectionRequired -> res.intent
            else -> return null
        }
        val correctionsRes = buildCorrections()
        if (correctionsRes.isFailure) {
            _uiState.update { it.copy(correctionError = correctionsRes.exceptionOrNull()?.message) }
            return null
        }
        val corrections = correctionsRes.getOrThrow()
        _uiState.update { it.copy(correctionError = null) }
        return intent.withCorrections(corrections)
    }

    suspend fun loadCandidatesOrCourses() {
        val intent = currentActiveIntent() ?: return
        val gen = currentGeneration

        try {
            if (intent.kind == NoticeIntentKind.EXTRA) {
                val snapshot = scheduleRepository.observeTimetable().first()
                if (gen != currentGeneration) return
                _uiState.update {
                    it.copy(
                        availableCourses = snapshot.courses,
                        candidates = emptyList(),
                        selectedCandidateId = null,
                    )
                }
                return
            }

            val bases: List<ImportLogicalBaseDisplay>
            val snapshot: TimetableSnapshot
            val target: ImportTarget
            if (importRepository != null) {
                val targetView = importRepository.readImportTarget(1)
                bases = targetView.logicalBaseDetails
                snapshot = targetView.snapshot
                target = targetView.target
            } else {
                snapshot = scheduleRepository.observeTimetable().first()
                target = ImportTarget(snapshot.scopeToken, snapshot.settings.id, snapshot.revision, 5)
                bases = snapshot.courses.flatMap { course ->
                    course.meetings.map { meeting ->
                        val original = meeting.copy(id = 0, exceptions = emptyList())
                        val intervals = original.customTime?.let { listOf(it) }
                            ?: snapshot.periods.filter { it.number in original.sections }.map { TimeRange(it.start, it.end) }
                        ImportLogicalBaseDisplay(
                            com.lingion.sleepy.domain.imports.LogicalBaseReference.StoredLogicalBase(
                                snapshot.scopeToken, snapshot.settings.id, course.id, "fam-${course.id}-${meeting.id}",
                                ImportCanonical.digest(original)
                            ),
                            course.courseName, original, intervals,
                            meeting.teacher ?: course.teacher, meeting.room ?: course.room,
                            meeting.experimentProject, meeting.group
                        )
                    }
                }
            }

            val result = NoticeOccurrenceResolver.resolve(intent, snapshot, bases, target)
            if (gen != currentGeneration) return

            when (result) {
                is NoticeCandidateResult.Found -> {
                    _uiState.update {
                        it.copy(
                            candidates = result.candidates,
                            selectedCandidateId = null,
                            parseProblems = result.problems,
                        )
                    }
                }
                is NoticeCandidateResult.None -> {
                    _uiState.update {
                        it.copy(
                            candidates = emptyList(),
                            selectedCandidateId = null,
                            parseProblems = result.problems,
                        )
                    }
                }
                is NoticeCandidateResult.NotRequired -> {
                    _uiState.update {
                        it.copy(candidates = emptyList(), selectedCandidateId = null)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (gen == currentGeneration) {
                _uiState.update {
                    it.copy(
                        generalErrorMessage = "读取课表候选课时异常: ${e.message}",
                        candidates = emptyList(),
                    )
                }
            }
        }
    }

    fun selectCandidate(candidateId: NoticeCandidateId) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        val candidate = _uiState.value.candidates.firstOrNull { it.id == candidateId }
        val alreadyAdjusted = candidate?.alreadyAdjusted == true
        _uiState.update {
            it.copy(
                selectedCandidateId = candidateId,
                alreadyAdjustedCandidate = alreadyAdjusted,
                replaceExistingAdjustment = false,
                detectedConflicts = emptyList(),
                acknowledgedConflictFingerprints = emptySet(),
            )
        }
    }

    fun selectExtraCourse(courseId: Long) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        _uiState.update {
            it.copy(
                selectedExtraCourseId = courseId,
                detectedConflicts = emptyList(),
                acknowledgedConflictFingerprints = emptySet(),
            )
        }
    }

    fun setReplaceExistingAdjustment(replace: Boolean) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        _uiState.update { it.copy(replaceExistingAdjustment = replace) }
    }

    fun acknowledgeConflict(fingerprint: String, acknowledged: Boolean) {
        if (_uiState.value.isApplying) return
        nextGeneration()
        clearConfirmationAndResults()
        _uiState.update {
            val updated = if (acknowledged) {
                it.acknowledgedConflictFingerprints + fingerprint
            } else {
                it.acknowledgedConflictFingerprints - fingerprint
            }
            it.copy(acknowledgedConflictFingerprints = updated)
        }
    }

    fun preview() {
        if (_uiState.value.isApplying) return
        val gen = nextGeneration()
        clearConfirmationAndResults()

        val intent = currentActiveIntent()
        if (intent == null) {
            val err = _uiState.value.correctionError ?: "请先解析通知并核对必要字段"
            _uiState.update { it.copy(generalErrorMessage = err) }
            return
        }

        val state = _uiState.value
        val selection = state.selectedCandidateId
        val extraCourse = state.selectedExtraCourseId?.let { NoticeCourseSelection(it) }

        if (intent.kind == NoticeIntentKind.EXTRA && extraCourse == null) {
            _uiState.update {
                it.copy(previewProblems = listOf(NoticeProblem(NoticeCodes.NEED_COURSE_SELECTION, "补课必须选择已有课程")))
            }
            return
        }

        if (intent.kind != NoticeIntentKind.EXTRA && selection == null) {
            _uiState.update {
                it.copy(previewProblems = listOf(NoticeProblem(NoticeCodes.NEED_MANUAL_SELECTION, "请先在候选中明确选择要调整的课时")))
            }
            return
        }

        _uiState.update { it.copy(isPreviewing = true) }

        previewJob = viewModelScope.launch {
            try {
                val target = if (importRepository != null) {
                    importRepository.readImportTarget(1).target
                } else {
                    val snap = scheduleRepository.observeTimetable().first()
                    ImportTarget(snap.scopeToken, snap.settings.id, snap.revision, 5)
                }

                val request = NoticeAdjustmentRequest(
                    target = target,
                    intent = intent,
                    selection = selection,
                    extraCourse = extraCourse,
                    acknowledgedConflicts = state.acknowledgedConflictFingerprints,
                    replaceExistingAdjustment = state.replaceExistingAdjustment,
                )

                val result = noticeRepository.preview(request)
                if (gen != currentGeneration) return@launch

                when (result) {
                    is NoticeAdjustmentPreviewResult.Ready -> {
                        _uiState.update {
                            it.copy(
                                isPreviewing = false,
                                preview = result.preview,
                                previewProblems = emptyList(),
                                detectedConflicts = result.preview.proposal.conflicts,
                                isAlreadyApplied = result.preview.alreadyAppliedEventId != null,
                                alreadyAppliedEventId = result.preview.alreadyAppliedEventId,
                            )
                        }
                    }
                    is NoticeAdjustmentPreviewResult.ConflictPending -> {
                        _uiState.update {
                            it.copy(
                                isPreviewing = false,
                                preview = null,
                                previewProblems = result.problems,
                                detectedConflicts = result.proposal.conflicts,
                            )
                        }
                    }
                    is NoticeAdjustmentPreviewResult.Blocked -> {
                        _uiState.update {
                            it.copy(
                                isPreviewing = false,
                                preview = null,
                                previewProblems = result.problems,
                                candidates = if (result.candidates.isNotEmpty()) result.candidates else it.candidates,
                            )
                        }
                    }
                    is NoticeAdjustmentPreviewResult.AlreadyApplied -> {
                        _uiState.update {
                            it.copy(
                                isPreviewing = false,
                                preview = null,
                                isAlreadyApplied = true,
                                alreadyAppliedEventId = result.eventId,
                                previewProblems = listOf(NoticeProblem(NoticeAdjustmentCodes.ALREADY_APPLIED, "该调整已应用")),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (gen == currentGeneration) {
                    _uiState.update {
                        it.copy(
                            isPreviewing = false,
                            preview = null,
                            generalErrorMessage = "生成预览失败: ${e.message}",
                        )
                    }
                }
            } finally {
                if (gen == currentGeneration) {
                    _uiState.update { it.copy(isPreviewing = false) }
                }
            }
        }
    }

    fun apply() {
        val preview = _uiState.value.preview
        if (preview == null || _uiState.value.isApplying) return

        val confirmation = preview.confirmation
        _uiState.update {
            it.copy(isApplying = true, applyErrorMessage = null, appliedSuccessMessage = null)
        }

        applyJob = viewModelScope.launch {
            try {
                val result = noticeRepository.apply(confirmation)
                when (result) {
                    is NoticeAdjustmentApplyResult.Applied -> {
                        _uiState.update {
                            it.copy(
                                isApplying = false,
                                preview = null,
                                appliedSuccessMessage = "调课已成功应用（仅此一次）",
                                appliedEventId = result.eventId,
                            )
                        }
                    }
                    is NoticeAdjustmentApplyResult.AlreadyApplied -> {
                        _uiState.update {
                            it.copy(
                                isApplying = false,
                                preview = null,
                                isAlreadyApplied = true,
                                alreadyAppliedEventId = result.eventId,
                                appliedSuccessMessage = "该调课此前已生效（仅此一次），未重复写入",
                            )
                        }
                    }
                    is NoticeAdjustmentApplyResult.Rejected -> {
                        _uiState.update {
                            it.copy(
                                isApplying = false,
                                preview = null,
                                applyErrorMessage = formatNoticeCode(result.code, result.detail),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                _uiState.update {
                    it.copy(
                        isApplying = false,
                        generalErrorMessage = "应用操作已取消或中断，请核对当前课表与历史记录",
                    )
                }
                throw cancelled
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isApplying = false,
                        applyErrorMessage = "应用异常: ${e.message}",
                    )
                }
            }
        }
    }
}

data class NoticeAdjustmentHistoryUiState(
    val isLoading: Boolean = false,
    val entries: List<NoticeAdjustmentHistoryEntry> = emptyList(),
    val nextBeforeEventId: Long? = null,
    val errorMessage: String? = null,
    val isUndoing: Boolean = false,
    val undoSuccessMessage: String? = null,
    val expandedSourceEventIds: Set<Long> = emptySet(),
    val confirmUndoEntry: NoticeAdjustmentHistoryEntry? = null,
)

class NoticeAdjustmentHistoryViewModel(
    private val noticeRepository: NoticeAdjustmentRepository,
    private val scheduleRepository: ScheduleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoticeAdjustmentHistoryUiState())
    val uiState: StateFlow<NoticeAdjustmentHistoryUiState> = _uiState.asStateFlow()

    class Factory(private val dependencies: TimetableDependencies) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val noticeRepo = checkNotNull(dependencies.noticeAdjustments) {
                "NoticeAdjustmentRepository is required"
            }
            return NoticeAdjustmentHistoryViewModel(
                noticeRepository = noticeRepo,
                scheduleRepository = dependencies.repository,
            ) as T
        }
    }

    init {
        loadHistory(clearSuccessMessage = false)
    }

    fun loadHistory(clearSuccessMessage: Boolean = false) {
        if (_uiState.value.isLoading) return
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                undoSuccessMessage = if (clearSuccessMessage) null else it.undoSuccessMessage,
            )
        }
        viewModelScope.launch {
            try {
                when (val result = noticeRepository.history(limit = 50, beforeEventId = null)) {
                    is NoticeAdjustmentHistoryResult.Loaded -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                entries = result.history.entries,
                                nextBeforeEventId = result.history.nextBeforeEventId,
                            )
                        }
                    }
                    is NoticeAdjustmentHistoryResult.Failed -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = formatNoticeCode(result.code),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "读取历史异常: ${e.message}",
                    )
                }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun loadMore() {
        val beforeId = _uiState.value.nextBeforeEventId ?: return
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                when (val result = noticeRepository.history(limit = 50, beforeEventId = beforeId)) {
                    is NoticeAdjustmentHistoryResult.Loaded -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                entries = it.entries + result.history.entries,
                                nextBeforeEventId = result.history.nextBeforeEventId,
                            )
                        }
                    }
                    is NoticeAdjustmentHistoryResult.Failed -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = formatNoticeCode(result.code),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "加载更多历史异常: ${e.message}",
                    )
                }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun toggleSourceExpand(eventId: Long) {
        _uiState.update {
            val current = it.expandedSourceEventIds
            val updated = if (eventId in current) current - eventId else current + eventId
            it.copy(expandedSourceEventIds = updated)
        }
    }

    fun promptUndo(entry: NoticeAdjustmentHistoryEntry) {
        _uiState.update { it.copy(confirmUndoEntry = entry, errorMessage = null, undoSuccessMessage = null) }
    }

    fun dismissUndoPrompt() {
        _uiState.update { it.copy(confirmUndoEntry = null) }
    }

    fun confirmUndo() {
        val entry = _uiState.value.confirmUndoEntry ?: return
        if (_uiState.value.isUndoing) return
        _uiState.update { it.copy(isUndoing = true, confirmUndoEntry = null, errorMessage = null) }

        viewModelScope.launch {
            try {
                val snapshot = scheduleRepository.observeTimetable().first()
                val revision = snapshot.revision
                val result = noticeRepository.undo(entry.eventId, revision)
                when (result) {
                    is NoticeAdjustmentUndoResult.Undone -> {
                        _uiState.update {
                            it.copy(
                                isUndoing = false,
                                undoSuccessMessage = "调课记录 #${entry.eventId} 已成功撤销，原安排已还原",
                            )
                        }
                        loadHistory(clearSuccessMessage = false)
                    }
                    is NoticeAdjustmentUndoResult.AlreadyUndone -> {
                        _uiState.update {
                            it.copy(
                                isUndoing = false,
                                undoSuccessMessage = "该调课记录此前已被撤销",
                            )
                        }
                        loadHistory(clearSuccessMessage = false)
                    }
                    is NoticeAdjustmentUndoResult.Rejected -> {
                        _uiState.update {
                            it.copy(
                                isUndoing = false,
                                errorMessage = formatNoticeCode(result.code, result.detail),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                _uiState.update {
                    it.copy(isUndoing = false, errorMessage = "撤销操作被中断，请刷新课表核对")
                }
                throw cancelled
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isUndoing = false, errorMessage = "撤销异常: ${e.message}")
                }
            } finally {
                _uiState.update { it.copy(isUndoing = false) }
            }
        }
    }
}
