/* SPDX-License-Identifier: GPL-3.0-only
 * ViewModel for manual single-occurrence course adjustments (adjust, cancel, extra).
 * Directly constructs role-complete NoticeIntent and executes strictly through NoticeAdjustmentRepository.
 */
package com.lingion.sleepy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.ImportLogicalBaseDisplay
import com.lingion.sleepy.domain.imports.ImportTarget
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.notice.NoticeAdjustmentApplyResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreview
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreviewResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentRequest
import com.lingion.sleepy.domain.notice.NoticeCandidate
import com.lingion.sleepy.domain.notice.NoticeCandidateId
import com.lingion.sleepy.domain.notice.NoticeCandidateResult
import com.lingion.sleepy.domain.notice.NoticeClause
import com.lingion.sleepy.domain.notice.NoticeConflict
import com.lingion.sleepy.domain.notice.NoticeCourseSelection
import com.lingion.sleepy.domain.notice.NoticeIntent
import com.lingion.sleepy.domain.notice.NoticeOccurrenceResolver
import com.lingion.sleepy.domain.notice.NoticeProblem
import com.lingion.sleepy.importing.DraftTimeRange
import com.lingion.sleepy.importing.NoticeDateBasis
import com.lingion.sleepy.importing.NoticeIntentKind
import com.lingion.sleepy.importing.NoticeRoomChange
import com.lingion.sleepy.importing.NoticeRoomRole
import com.lingion.sleepy.importing.NoticeScope
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

enum class ManualOccurrenceMode(val label: String) {
    ADJUST("调整一次"),
    CANCEL("停课一次"),
    EXTRA("补课一次"),
}

data class ManualOccurrenceUiState(
    val courseId: Long = 0L,
    val courseName: String = "",
    val mode: ManualOccurrenceMode = ManualOccurrenceMode.ADJUST,
    val referenceDate: LocalDate = LocalDate.now(),
    val termMonday: LocalDate? = null,
    val totalWeeks: Int = 0,
    // Available dates on which this course occurs in the term
    val courseOccurrenceDates: List<LocalDate> = emptyList(),
    // Selected original occurrence date for ADJUST or CANCEL
    val originalDate: LocalDate? = null,
    val candidates: List<NoticeCandidate> = emptyList(),
    val selectedCandidateId: NoticeCandidateId? = null,
    val isLoadingCandidates: Boolean = false,
    val candidateMessage: String? = null,
    // Target inputs
    val targetDateInput: String = "",
    val targetSectionsInput: String = "",
    // Custom clock toggle and inputs
    val useCustomTime: Boolean = false,
    val customTimeStartInput: String = "",
    val customTimeEndInput: String = "",
    val targetRoomInput: String = "",
    // Reconcile and conflicts
    val alreadyAdjustedCandidate: Boolean = false,
    val replaceExistingAdjustment: Boolean = false,
    val detectedConflicts: List<NoticeConflict> = emptyList(),
    val acknowledgedConflictFingerprints: Set<String> = emptySet(),
    // Preview & Apply
    val isPreviewing: Boolean = false,
    val preview: NoticeAdjustmentPreview? = null,
    val previewProblems: List<NoticeProblem> = emptyList(),
    val isApplying: Boolean = false,
    val applySuccessMessage: String? = null,
    val applyErrorMessage: String? = null,
    // Metadata loading state
    val isLoadingMetadata: Boolean = false,
    val metadataError: String? = null,
)

class ManualOccurrenceViewModel(
    private val dependencies: TimetableDependencies,
    private var boundCourseId: Long,
    initialMode: ManualOccurrenceMode = ManualOccurrenceMode.ADJUST,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManualOccurrenceUiState(courseId = boundCourseId, mode = initialMode))
    val uiState: StateFlow<ManualOccurrenceUiState> = _uiState.asStateFlow()

    private var currentSnapshot: TimetableSnapshot? = null
    private var currentTarget: ImportTarget? = null
    private var currentBases: List<ImportLogicalBaseDisplay> = emptyList()

    private var currentGeneration = 0L
    private var previewJob: Job? = null
    private var candidateJob: Job? = null
    private var applyJob: Job? = null

    init {
        loadCourseAndMetadata()
    }

    private fun nextGeneration(keepSuccessMessage: Boolean = false): Long {
        currentGeneration++
        previewJob?.cancel()
        previewJob = null
        candidateJob?.cancel()
        candidateJob = null
        _uiState.update {
            it.copy(
                isPreviewing = false,
                preview = null,
                previewProblems = emptyList(),
                applySuccessMessage = if (keepSuccessMessage) it.applySuccessMessage else null,
                applyErrorMessage = null,
            )
        }
        return currentGeneration
    }

    private fun canModifyInput(): Boolean = !_uiState.value.isApplying

    fun bindCourse(courseId: Long, mode: ManualOccurrenceMode) {
        if (boundCourseId == courseId && _uiState.value.mode == mode) return
        boundCourseId = courseId
        nextGeneration()
        _uiState.value = ManualOccurrenceUiState(courseId = courseId, mode = mode)
        loadCourseAndMetadata(keepSuccessMessage = false)
    }

    fun retryLoadMetadata() {
        if (!canModifyInput()) return
        nextGeneration()
        loadCourseAndMetadata(keepSuccessMessage = false)
    }

    fun setMode(mode: ManualOccurrenceMode) {
        if (!canModifyInput()) return
        nextGeneration()
        _uiState.update {
            it.copy(
                mode = mode,
                detectedConflicts = emptyList(),
                acknowledgedConflictFingerprints = emptySet(),
                candidates = if (mode == ManualOccurrenceMode.EXTRA) emptyList() else it.candidates,
                selectedCandidateId = if (mode == ManualOccurrenceMode.EXTRA) null else it.selectedCandidateId,
                originalDate = if (mode == ManualOccurrenceMode.EXTRA) null else it.originalDate,
                targetDateInput = if (mode == ManualOccurrenceMode.EXTRA) it.referenceDate.toString() else it.targetDateInput,
            )
        }
        if (mode != ManualOccurrenceMode.EXTRA && _uiState.value.originalDate != null) {
            loadCandidatesForOriginalDate(_uiState.value.originalDate!!)
        }
    }

    fun setOriginalDate(date: LocalDate) {
        if (!canModifyInput()) return
        nextGeneration()
        _uiState.update {
            it.copy(
                originalDate = date,
                targetDateInput = if (it.mode == ManualOccurrenceMode.ADJUST) date.toString() else it.targetDateInput,
                selectedCandidateId = null,
                candidates = emptyList(),
                detectedConflicts = emptyList(),
                acknowledgedConflictFingerprints = emptySet(),
            )
        }
        loadCandidatesForOriginalDate(date)
    }

    fun selectCandidate(id: NoticeCandidateId) {
        if (!canModifyInput()) return
        val cand = _uiState.value.candidates.singleOrNull { it.id == id } ?: return
        nextGeneration()
        val isCustomTimeOnly = cand.currentSections.isEmpty() && cand.currentRanges.isNotEmpty()
        _uiState.update {
            it.copy(
                selectedCandidateId = id,
                alreadyAdjustedCandidate = cand.alreadyAdjusted,
                replaceExistingAdjustment = false,
                targetSectionsInput = cand.currentSections.sorted().joinToString(","),
                targetRoomInput = cand.currentRoom,
                // 不自动将多段范围填为自定义时钟！仅当本身为无节次课程时自动展示其真实时钟
                useCustomTime = isCustomTimeOnly,
                customTimeStartInput = if (isCustomTimeOnly) cand.currentRanges.first().start.toString() else "",
                customTimeEndInput = if (isCustomTimeOnly) cand.currentRanges.last().end.toString() else "",
                detectedConflicts = emptyList(),
                acknowledgedConflictFingerprints = emptySet(),
            )
        }
    }

    fun setUseCustomTime(enabled: Boolean) {
        if (!canModifyInput()) return
        nextGeneration()
        _uiState.update { it.copy(useCustomTime = enabled) }
    }

    fun setTargetDateInput(input: String) {
        if (!canModifyInput()) return
        nextGeneration()
        _uiState.update { it.copy(targetDateInput = input) }
    }

    fun setTargetSectionsInput(input: String) {
        if (!canModifyInput()) return
        nextGeneration()
        _uiState.update { it.copy(targetSectionsInput = input) }
    }

    fun setCustomTimeStartInput(input: String) {
        if (!canModifyInput()) return
        nextGeneration()
        _uiState.update { it.copy(customTimeStartInput = input) }
    }

    fun setCustomTimeEndInput(input: String) {
        if (!canModifyInput()) return
        nextGeneration()
        _uiState.update { it.copy(customTimeEndInput = input) }
    }

    fun setTargetRoomInput(input: String) {
        if (!canModifyInput()) return
        nextGeneration()
        _uiState.update { it.copy(targetRoomInput = input) }
    }

    fun setReplaceExistingAdjustment(replace: Boolean) {
        if (!canModifyInput()) return
        nextGeneration()
        _uiState.update { it.copy(replaceExistingAdjustment = replace) }
    }

    fun acknowledgeConflict(fingerprint: String, acknowledged: Boolean) {
        if (!canModifyInput()) return
        nextGeneration()
        _uiState.update {
            val current = it.acknowledgedConflictFingerprints.toMutableSet()
            if (acknowledged) current.add(fingerprint) else current.remove(fingerprint)
            it.copy(acknowledgedConflictFingerprints = current)
        }
    }

    fun preview() {
        val s = _uiState.value
        val repo = dependencies.noticeAdjustments
        if (repo == null) {
            _uiState.update { it.copy(previewProblems = listOf(NoticeProblem("NO_REPO", "调课服务不可用"))) }
            return
        }
        if (s.isPreviewing || s.isApplying) return

        // 验证原日期与候选选择（ADJUST 与 CANCEL 必需明确选择）
        if (s.mode != ManualOccurrenceMode.EXTRA) {
            if (s.originalDate == null) {
                _uiState.update { it.copy(previewProblems = listOf(NoticeProblem("NEED_ORIGINAL_DATE", "请先选择需要操作的具体上课日期"))) }
                return
            }
            if (s.selectedCandidateId == null) {
                _uiState.update { it.copy(previewProblems = listOf(NoticeProblem("NEED_MANUAL_SELECTION", "请在候选课时列表中明确点击选择要操作的课时"))) }
                return
            }
        }

        // 解析目标日期
        val targetDate = if (s.mode == ManualOccurrenceMode.CANCEL) {
            s.originalDate!!
        } else {
            val parsed = parseDateStrict(s.targetDateInput)
            if (parsed.isFailure || parsed.getOrNull() == null) {
                _uiState.update {
                    it.copy(previewProblems = listOf(NoticeProblem("INVALID_TARGET_DATE", parsed.exceptionOrNull()?.message ?: "目标日期不能为空")))
                }
                return
            }
            parsed.getOrNull()!!
        }

        val chosenCand = s.candidates.singleOrNull { it.id == s.selectedCandidateId }
        val originalSections = chosenCand?.currentSections ?: emptySet()
        // 原时间：仅当原发生恰好只有一段时表达为 DraftTimeRange，多段时为 null（保持原始 sections，不捏造首段范围）
        val originalTime = if (chosenCand != null && chosenCand.currentRanges.size == 1) {
            DraftTimeRange(chosenCand.currentRanges[0].start, chosenCand.currentRanges[0].end)
        } else null

        val originalRoom = chosenCand?.currentRoom ?: ""
        val targetRoom = s.targetRoomInput.trim()
        val roomChange = if (s.mode == ManualOccurrenceMode.ADJUST && targetRoom != originalRoom && targetRoom.isNotBlank()) {
            NoticeRoomChange(NoticeRoomRole.CHANGE, from = originalRoom, to = targetRoom)
        } else null

        // 核心：区分三种时间模式（保持有效时间、用户明确改节次、用户明确自定义时钟）
        val targetSections: Set<Int>
        val targetTime: DraftTimeRange?

        if (s.mode == ManualOccurrenceMode.CANCEL) {
            targetSections = emptySet()
            targetTime = null
        } else {
            if (s.useCustomTime) {
                // 用户显式自定义时钟模式（或无节次课程）
                val parsedSecResult = parseSectionsStrict(s.targetSectionsInput)
                if (parsedSecResult.isFailure) {
                    _uiState.update {
                        it.copy(previewProblems = listOf(NoticeProblem("INVALID_TARGET_SECTIONS", parsedSecResult.exceptionOrNull()?.message ?: "节次格式无效")))
                    }
                    return
                }
                targetSections = parsedSecResult.getOrNull().orEmpty()

                val parsedTime = parseTimeRangeStrict(s.customTimeStartInput, s.customTimeEndInput)
                if (parsedTime.isFailure || parsedTime.getOrNull() == null) {
                    _uiState.update {
                        it.copy(previewProblems = listOf(NoticeProblem("INVALID_TARGET_TIME", parsedTime.exceptionOrNull()?.message ?: "请填写有效的自定义起止时间")))
                    }
                    return
                }
                targetTime = parsedTime.getOrNull()
            } else {
                // 默认/节次模式：targetTime 严格为 null，绝不造 clock 包络！
                targetTime = null
                val parsedSecResult = parseSectionsStrict(s.targetSectionsInput)
                if (parsedSecResult.isFailure) {
                    _uiState.update {
                        it.copy(previewProblems = listOf(NoticeProblem("INVALID_TARGET_SECTIONS", parsedSecResult.exceptionOrNull()?.message ?: "必须输入有效的节次（如 3-4 或 5）")))
                    }
                    return
                }
                val parsedSec = parsedSecResult.getOrNull()
                if (s.mode == ManualOccurrenceMode.EXTRA) {
                    if (parsedSec.isNullOrEmpty()) {
                        _uiState.update {
                            it.copy(previewProblems = listOf(NoticeProblem("NEED_SECTIONS_OR_TIME", "补课必须指定节次或开启自定义起止时间")))
                        }
                        return
                    }
                    targetSections = parsedSec
                } else {
                    // ADJUST 模式：若用户未修改节次（解析后与原节次相同或为空），提交 emptySet()，底层直接完整保留原 intervals 与课间！
                    if (parsedSec == null || parsedSec == originalSections) {
                        targetSections = emptySet()
                    } else {
                        targetSections = parsedSec
                    }
                }
            }
        }

        // 构造角色明确的 NoticeIntentKind
        val intentKind = when (s.mode) {
            ManualOccurrenceMode.CANCEL -> NoticeIntentKind.CANCEL
            ManualOccurrenceMode.EXTRA -> NoticeIntentKind.EXTRA
            ManualOccurrenceMode.ADJUST -> when {
                s.originalDate != null && s.originalDate != targetDate -> NoticeIntentKind.CHANGE_DATE
                targetSections.isNotEmpty() && targetSections != originalSections -> NoticeIntentKind.RESCHEDULE_TIME
                targetTime != null && targetTime != originalTime -> NoticeIntentKind.RESCHEDULE_TIME
                roomChange != null -> NoticeIntentKind.ROOM_ONLY
                else -> NoticeIntentKind.RESCHEDULE_TIME
            }
        }

        val intent = NoticeIntent(
            kind = intentKind,
            rawText = "手动操作：${s.mode.label} · ${s.courseName}",
            clauses = listOf(NoticeClause(sourceId = null, sourceLocator = "manual", rawText = "手动单次操作")),
            basis = NoticeDateBasis(
                referenceDate = s.referenceDate,
                termMonday = s.termMonday,
                totalWeeks = s.totalWeeks,
            ),
            courseNameHint = s.courseName,
            teacher = chosenCand?.currentTeacher,
            room = if (targetRoom.isNotBlank()) targetRoom else chosenCand?.currentRoom,
            group = chosenCand?.currentGroup,
            project = chosenCand?.currentProject,
            originalDate = s.originalDate,
            targetDate = targetDate,
            original = NoticeScope(originalSections, originalTime),
            target = NoticeScope(targetSections, targetTime),
            roomChange = roomChange,
            bigSection = null,
            declaredWeeks = emptySet(),
            declaredDayOfWeek = targetDate.dayOfWeek.value,
            dateRule = null,
            sameDayAdjustment = (s.originalDate != null && s.originalDate == targetDate),
        )

        val gen = nextGeneration()
        _uiState.update { it.copy(isPreviewing = true, previewProblems = emptyList(), applyErrorMessage = null) }

        previewJob = viewModelScope.launch {
            try {
                // 确保获取最新 snapshot 与 target
                val snap = dependencies.repository.observeTimetable().first()
                val target = dependencies.imports?.readImportTarget(snap.settings.id)?.target
                    ?: ImportTarget(snap.scopeToken, snap.settings.id, snap.revision, 5)

                val request = NoticeAdjustmentRequest(
                    target = target,
                    intent = intent,
                    selection = s.selectedCandidateId,
                    extraCourse = if (s.mode == ManualOccurrenceMode.EXTRA) NoticeCourseSelection(boundCourseId) else null,
                    acknowledgedConflicts = s.acknowledgedConflictFingerprints,
                    replaceExistingAdjustment = s.replaceExistingAdjustment,
                )

                val result = repo.preview(request)
                if (gen != currentGeneration) return@launch

                when (result) {
                    is NoticeAdjustmentPreviewResult.Ready -> {
                        _uiState.update {
                            it.copy(
                                isPreviewing = false,
                                preview = result.preview,
                                previewProblems = emptyList(),
                                detectedConflicts = result.preview.proposal.conflicts,
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
                                detectedConflicts = emptyList(),
                            )
                        }
                    }
                    is NoticeAdjustmentPreviewResult.AlreadyApplied -> {
                        _uiState.update {
                            it.copy(
                                isPreviewing = false,
                                preview = null,
                                previewProblems = listOf(NoticeProblem("ALREADY_APPLIED", "该操作此前已应用生效（事件 #${result.eventId}）")),
                                detectedConflicts = emptyList(),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (gen != currentGeneration) return@launch
                _uiState.update {
                    it.copy(
                        isPreviewing = false,
                        previewProblems = listOf(NoticeProblem("PREVIEW_ERROR", e.message ?: "预览失败")),
                    )
                }
            }
        }
    }

    fun apply() {
        val s = _uiState.value
        val confirmation = s.preview?.confirmation ?: return
        val repo = dependencies.noticeAdjustments ?: return
        if (s.isApplying) return

        _uiState.update { it.copy(isApplying = true, applyErrorMessage = null, applySuccessMessage = null) }

        applyJob = viewModelScope.launch {
            try {
                when (val result = repo.apply(confirmation)) {
                    is NoticeAdjustmentApplyResult.Applied -> {
                        _uiState.update {
                            it.copy(
                                isApplying = false,
                                preview = null,
                                applySuccessMessage = "操作成功！已写入单次变更（事件 #${result.eventId}），课表已自动刷新。",
                            )
                        }
                        // 提交成功后重新刷新最新快照与元数据，防 STALE_TARGET，同时保留成功提示
                        loadCourseAndMetadata(keepSuccessMessage = true)
                    }
                    is NoticeAdjustmentApplyResult.AlreadyApplied -> {
                        _uiState.update {
                            it.copy(
                                isApplying = false,
                                preview = null,
                                applySuccessMessage = "该调整此前已生效（事件 #${result.eventId}），无须重复写入。",
                            )
                        }
                        loadCourseAndMetadata(keepSuccessMessage = true)
                    }
                    is NoticeAdjustmentApplyResult.Rejected -> {
                        _uiState.update {
                            it.copy(
                                isApplying = false,
                                preview = null,
                                applyErrorMessage = "提交被拒绝：${result.code} ${result.detail.orEmpty()}。请重新核对后预览。",
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isApplying = false,
                        preview = null,
                        applyErrorMessage = "提交出现异常：${e.message}。请重试。",
                    )
                }
            }
        }
    }

    private fun loadCourseAndMetadata(keepSuccessMessage: Boolean = false) {
        val gen = nextGeneration(keepSuccessMessage = keepSuccessMessage)
        _uiState.update { it.copy(isLoadingMetadata = true, metadataError = null) }

        viewModelScope.launch {
            try {
                val snapshot = dependencies.repository.observeTimetable().first()
                if (gen != currentGeneration) return@launch
                currentSnapshot = snapshot

                val course = snapshot.courses.singleOrNull { it.id == boundCourseId }
                val termMonday = snapshot.settings.startDate?.let(TimetableRules::mondayOf)
                val totalWeeks = snapshot.settings.totalWeeks

                val occurrenceDates = if (course != null && termMonday != null && totalWeeks > 0) {
                    calculateCourseDates(course, snapshot)
                } else emptyList()

                val importRepo = dependencies.imports
                if (importRepo != null) {
                    try {
                        val targetView = importRepo.readImportTarget(snapshot.settings.id)
                        if (gen != currentGeneration) return@launch
                        currentTarget = targetView.target
                        currentBases = targetView.logicalBaseDetails
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (gen != currentGeneration) return@launch
                        _uiState.update {
                            it.copy(
                                isLoadingMetadata = false,
                                metadataError = "无法读取导入目标：${e.message}",
                            )
                        }
                        return@launch
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoadingMetadata = false,
                            metadataError = "导入目标服务不可用，无法进行单次操作",
                        )
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        isLoadingMetadata = false,
                        courseName = course?.courseName ?: "未知课程",
                        termMonday = termMonday,
                        totalWeeks = totalWeeks,
                        courseOccurrenceDates = occurrenceDates,
                        targetRoomInput = course?.room.orEmpty(),
                        metadataError = null,
                    )
                }
                // 初始化坚决不静默默认选中第一个日期！等待用户明确点击选择
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (gen != currentGeneration) return@launch
                _uiState.update {
                    it.copy(
                        isLoadingMetadata = false,
                        metadataError = "加载课程元数据失败：${e.message}",
                    )
                }
            }
        }
    }

    private fun loadCandidatesForOriginalDate(date: LocalDate) {
        val snapshot = currentSnapshot ?: return
        val target = currentTarget ?: return
        val bases = currentBases
        val gen = nextGeneration()

        _uiState.update { it.copy(isLoadingCandidates = true, candidateMessage = null) }

        candidateJob = viewModelScope.launch {
            try {
                val course = snapshot.courses.singleOrNull { it.id == boundCourseId }
                val intent = NoticeIntent(
                    kind = NoticeIntentKind.CANCEL, // 非 EXTRA 均可用于 resolve 候选发生
                    rawText = "检索候选",
                    clauses = emptyList(),
                    basis = NoticeDateBasis(
                        referenceDate = _uiState.value.referenceDate,
                        termMonday = _uiState.value.termMonday,
                        totalWeeks = _uiState.value.totalWeeks,
                    ),
                    courseNameHint = course?.courseName,
                    teacher = null, room = null, group = null, project = null,
                    originalDate = date, targetDate = date,
                    original = NoticeScope(emptySet(), null),
                    target = NoticeScope(emptySet(), null),
                    roomChange = null, bigSection = null,
                    declaredWeeks = emptySet(), declaredDayOfWeek = date.dayOfWeek.value,
                    dateRule = null, sameDayAdjustment = true,
                )

                val res = NoticeOccurrenceResolver.resolve(intent, snapshot, bases, target)
                if (gen != currentGeneration) return@launch

                when (res) {
                    is NoticeCandidateResult.Found -> {
                        val matching = res.candidates.filter { it.id.courseId == boundCourseId }
                        _uiState.update {
                            it.copy(
                                isLoadingCandidates = false,
                                candidates = matching,
                                selectedCandidateId = null, // 唯一候选也不静默自动选中，需用户明确点击
                                candidateMessage = if (matching.isEmpty()) "选定日期未找到「${course?.courseName}」的课时安排" else null,
                            )
                        }
                    }
                    is NoticeCandidateResult.None -> {
                        _uiState.update {
                            it.copy(
                                isLoadingCandidates = false,
                                candidates = emptyList(),
                                selectedCandidateId = null,
                                candidateMessage = "未找到该日期的课时安排：${res.problems.joinToString { p -> p.code }}",
                            )
                        }
                    }
                    is NoticeCandidateResult.NotRequired -> {
                        _uiState.update { it.copy(isLoadingCandidates = false) }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (gen != currentGeneration) return@launch
                _uiState.update {
                    it.copy(
                        isLoadingCandidates = false,
                        candidateMessage = "检索候选异常：${e.message}",
                    )
                }
            }
        }
    }

    private fun calculateCourseDates(course: ScheduledCourse, snapshot: TimetableSnapshot): List<LocalDate> {
        val dates = mutableSetOf<LocalDate>()
        for (meeting in course.meetings) {
            when (meeting.kind) {
                MeetingKind.RECURRING -> {
                    for (week in meeting.weeks) {
                        TimetableRules.dateOfWeek(snapshot.settings, week, meeting.day)?.let { dates.add(it) }
                    }
                }
                MeetingKind.DATE_SPECIFIC -> {
                    dates.addAll(meeting.dates)
                }
                MeetingKind.UNSCHEDULED -> {}
            }
            // 包含例外中调入的日期（如 EXTRA 补课或目标日）
            for (ex in meeting.exceptions) {
                ex.targetDate?.let { dates.add(it) }
            }
        }
        return dates.sorted()
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

    class Factory(
        private val dependencies: TimetableDependencies,
        private val courseId: Long,
        private val initialMode: ManualOccurrenceMode = ManualOccurrenceMode.ADJUST,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ManualOccurrenceViewModel(dependencies, courseId, initialMode) as T
        }
    }
}
