/* SPDX-License-Identifier: GPL-3.0-only
 * UI state machine for single-occurrence adjustment history and guarded undo.
 * Shared across manual occurrence adjustments and adjustment history screens.
 */
package com.lingion.sleepy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.domain.notice.NoticeAdjustmentCodes
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryEntry
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentUndoResult
import com.lingion.sleepy.domain.notice.NoticeCodes
import com.lingion.sleepy.domain.repository.NoticeAdjustmentRepository
import com.lingion.sleepy.domain.repository.ScheduleRepository
import kotlinx.coroutines.CancellationException
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
