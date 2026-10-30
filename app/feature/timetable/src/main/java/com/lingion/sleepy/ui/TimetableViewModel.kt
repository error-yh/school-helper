package com.lingion.sleepy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.domain.repository.ScheduleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class TimetableOperation { COURSE_SAVED, COURSE_DELETED, SETTINGS_SAVED }
// Completion counters and their consumption share the same ViewModel lifetime.
internal class TimetableOperationTracker(initialSerial: Long) {
    private var handled = initialSerial
    fun consume(serial: Long): Boolean {
        if (serial <= handled) return false
        handled = serial
        return true
    }
}
internal data class TimetableUiState(
    val snapshot: TimetableSnapshot = TimetableSnapshot(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val hasReadError: Boolean = false,
    val errorMessage: String? = null,
    val message: String? = null,
    val operationSerial: Long = 0,
    val completedOperation: TimetableOperation? = null,
)
internal class TimetableViewModel(private val repository: ScheduleRepository) : ViewModel() {
    private val mutableUiState = MutableStateFlow(TimetableUiState())
    val uiState = mutableUiState.asStateFlow()
    private val operationTracker = TimetableOperationTracker(initialSerial = 0)
    private var observationJob: Job? = null
    init { retryLoading() }

    fun consumeCompletedOperation(): Boolean = operationTracker.consume(mutableUiState.value.operationSerial)

    fun retryLoading() {
        observationJob?.cancel()
        mutableUiState.update { it.copy(isLoading = true) }
        observationJob = viewModelScope.launch {
            try {
                repository.observeTimetable().collect { snapshot ->
                    mutableUiState.update { it.copy(snapshot = snapshot, isLoading = false,
                        hasReadError = false, errorMessage = if (it.hasReadError) null else it.errorMessage) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableUiState.update { it.copy(isLoading = false, hasReadError = true,
                    errorMessage = "暂时无法读取课程，请重试。") }
            }
        }
    }
    fun clearMessage() { mutableUiState.update { if (it.hasReadError) it else it.copy(errorMessage = null, message = null) } }
    fun saveCourse(draft: CourseDraft, id: Long? = null) {
        if (!canWrite()) return
        val snapshot = mutableUiState.value.snapshot
        val errors = TimetableRules.validateCourse(draft, snapshot.settings, snapshot.periods)
        if (errors.isNotEmpty()) { mutableUiState.update { it.copy(errorMessage = errors.joinToString("\n")) }; return }
        write(TimetableOperation.COURSE_SAVED, "课程已保存", "暂时无法保存课程，请重试。") {
            repository.saveCourse(draft, id)
        }
    }
    fun deleteCourse(id: Long) {
        write(TimetableOperation.COURSE_DELETED, "课程已删除", "暂时无法删除课程，请重试。") { repository.deleteCourse(id) }
    }
    fun saveSettings(settings: SemesterSettings, periods: List<Period>) {
        if (!canWrite()) return
        val errors = TimetableRules.validateSettings(settings) + TimetableRules.validatePeriods(periods)
        if (errors.isNotEmpty()) { mutableUiState.update { it.copy(errorMessage = errors.joinToString("\n")) }; return }
        write(TimetableOperation.SETTINGS_SAVED, "设置已保存", "暂时无法保存设置，请重试。") {
            repository.updateSettings(settings, periods)
        }
    }
    private fun canWrite(): Boolean = mutableUiState.value.let { !it.isSaving && !it.isLoading && !it.hasReadError }
    private fun write(operation: TimetableOperation, message: String, fallback: String, action: suspend () -> Unit) {
        if (!canWrite()) return
        mutableUiState.update { it.copy(isSaving = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            try {
                action()
                mutableUiState.update { it.copy(message = message, completedOperation = operation, operationSerial = it.operationSerial + 1) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableUiState.update {
                    if (it.hasReadError) it else it.copy(errorMessage = if (error is IllegalArgumentException) error.message ?: fallback else fallback)
                }
            } finally { mutableUiState.update { it.copy(isSaving = false) } }
        }
    }
    class Factory(private val repository: ScheduleRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TimetableViewModel::class.java)) { "Unsupported ViewModel class" }
            @Suppress("UNCHECKED_CAST")
            return TimetableViewModel(repository) as T
        }
    }
}
