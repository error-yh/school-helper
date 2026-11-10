/* SPDX-License-Identifier: GPL-3.0-only
 * Manages active timetable database connection and dynamically delegates repository calls.
 * Ensures that restoring a timetable backup safely closes the previous connection and binds new profile.
 */
package com.aggapp.app.di

import android.content.Context
import com.aggapp.app.migration.RecoveryResult
import com.aggapp.app.migration.TimetableRecoveryBarrier
import com.lingion.sleepy.data.RoomNoticeAdjustmentRepository
import com.lingion.sleepy.data.RoomScheduleRepository
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.domain.imports.ConflictReviewDecision
import com.lingion.sleepy.domain.imports.ImportApplyCommand
import com.lingion.sleepy.domain.imports.ReviewedImport
import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.notice.NoticeAdjustmentApplyResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentConfirmation
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreviewResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentRequest
import com.lingion.sleepy.domain.notice.NoticeAdjustmentUndoResult
import com.lingion.sleepy.domain.repository.ImportApplyResult
import com.lingion.sleepy.domain.repository.ImportPreviewResult
import com.lingion.sleepy.domain.repository.ImportRepository
import com.lingion.sleepy.domain.repository.ImportTargetView
import com.lingion.sleepy.domain.repository.NoticeAdjustmentRepository
import com.lingion.sleepy.domain.repository.ScheduleRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import android.net.Uri
import com.aggapp.app.migration.RestoreResult
import com.aggapp.app.migration.TimetableMigrationService
import com.lingion.sleepy.data.backup.TimetableConfirmationToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.lingion.sleepy.data.backup.TimetableWriteCoordinator
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TimetableActivatorEntryPoint {
    fun timetableProfileActivator(): TimetableProfileActivator
}

@Singleton
class TimetableProfileActivator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private class Profile(val id: String, val database: TimetableDatabase) {
        val schedule = RoomScheduleRepository(database)
        val notice = RoomNoticeAdjustmentRepository(database)
        var observers = 0
    }

    private val lifecycle = Mutex()
    private val active = MutableStateFlow<Profile?>(null)
    private var pendingProfileId: String? = null

    val scheduleRepository: ScheduleRepository = DelegatingScheduleRepository(this)
    val importRepository: ImportRepository = DelegatingScheduleRepository(this)
    val noticeAdjustmentRepository: NoticeAdjustmentRepository = DelegatingNoticeAdjustmentRepository(this)

    private suspend fun loadProfile(): Profile {
        pendingProfileId?.let { bindProfile(it) }
        active.value?.let { return it }
        return withContext(Dispatchers.IO) {
            TimetableWriteCoordinator.withSnapshotLease {
                val recovery = TimetableRecoveryBarrier.recoverBeforeOpen(context)
                check(recovery !is RecoveryResult.Blocked) { "课表启动恢复被阻断" }
                val prefs = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
                val id = prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
                    check(prefs.edit().putString("id", it).commit())
                }
                Profile(id, TimetableDatabase.open(context, id)).also { active.value = it }
            }
        }
    }

    suspend fun activeProfileId(): String = lifecycle.withLock { loadProfile().id }

    suspend fun <T> withScheduleRepo(block: suspend (RoomScheduleRepository) -> T): T =
        lifecycle.withLock { block(loadProfile().schedule) }

    suspend fun <T> withNoticeRepo(block: suspend (RoomNoticeAdjustmentRepository) -> T): T =
        lifecycle.withLock { block(loadProfile().notice) }

    internal suspend fun activateProfile(newProfileId: String) = lifecycle.withLock {
        bindProfile(newProfileId)
    }

    suspend fun restoreBackup(uri: Uri, passphrase: CharArray, token: TimetableConfirmationToken): RestoreResult =
        try {
            lifecycle.withLock {
                val result = try {
                    TimetableMigrationService.restore(context, uri, passphrase, token)
                } catch (cancelled: CancellationException) {
                    // IO dispatch can deliver cancellation after a successful durable commit.
                    withContext(NonCancellable + Dispatchers.IO) {
                        val recovery = TimetableWriteCoordinator.withSnapshotLease {
                            TimetableRecoveryBarrier.recoverBeforeOpen(context)
                        }
                        if (recovery is RecoveryResult.RestoredCommitted) {
                            bindProfile(recovery.receipt.restoredActiveProfileId)
                        }
                    }
                    throw cancelled
                }
                if (result is RestoreResult.Success) {
                    withContext(NonCancellable) { bindProfile(result.activeProfileId) }
                } else if (result is RestoreResult.AlreadyImported) {
                    val profileId = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
                        .getString("id", null)
                    if (profileId != null) bindProfile(profileId)
                }
                result
            }
        } finally {
            passphrase.fill('\u0000')
        }

    private suspend fun bindProfile(id: String) {
        val old = active.value
        if (old?.id == id) {
            pendingProfileId = null
            return
        }
        pendingProfileId = id
        withContext(Dispatchers.IO) {
            val database = TimetableDatabase.open(context, id)
            try {
                database.openHelper.readableDatabase
            } catch (e: Exception) {
                database.close()
                throw e
            }
            active.value = Profile(id, database)
            pendingProfileId = null
            if (old != null && old.observers == 0) old.database.close()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun observeTimetableDynamic(readOnly: Boolean = false): Flow<TimetableSnapshot> = flow {
        lifecycle.withLock { loadProfile() }
        emitAll(active.filterNotNull().flatMapLatest { profile ->
            flow {
                val acquired = lifecycle.withLock {
                    if (active.value !== profile) false else {
                        profile.observers++
                        true
                    }
                }
                if (!acquired) return@flow
                try {
                    emitAll(if (readOnly) profile.schedule.observeTimetableReadOnly() else profile.schedule.observeTimetable())
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        lifecycle.withLock {
                            profile.observers--
                            if (profile.observers == 0 && active.value !== profile) profile.database.close()
                        }
                    }
                }
            }
        })
    }
}

private class DelegatingScheduleRepository(
    private val activator: TimetableProfileActivator,
) : ScheduleRepository, ImportRepository {
    override fun observeTimetable(): Flow<TimetableSnapshot> =
        activator.observeTimetableDynamic()

    override suspend fun saveCourse(draft: CourseDraft, id: Long?): Long =
        activator.withScheduleRepo { it.saveCourse(draft, id) }

    override suspend fun deleteCourse(id: Long) =
        activator.withScheduleRepo { it.deleteCourse(id) }

    override suspend fun updateSettings(settings: SemesterSettings, periods: List<Period>) =
        activator.withScheduleRepo { it.updateSettings(settings, periods) }

    override suspend fun readImportTarget(semesterId: Long): ImportTargetView =
        activator.withScheduleRepo { it.readImportTarget(semesterId) }

    override suspend fun previewImport(
        reviewed: ReviewedImport,
        conflicts: List<ConflictReviewDecision>,
    ): ImportPreviewResult =
        activator.withScheduleRepo { it.previewImport(reviewed, conflicts) }

    override suspend fun applyImport(command: ImportApplyCommand): ImportApplyResult =
        activator.withScheduleRepo { it.applyImport(command) }
}

private class DelegatingNoticeAdjustmentRepository(
    private val activator: TimetableProfileActivator,
) : NoticeAdjustmentRepository {
    override suspend fun preview(request: NoticeAdjustmentRequest): NoticeAdjustmentPreviewResult =
        activator.withNoticeRepo { it.preview(request) }

    override suspend fun apply(confirmation: NoticeAdjustmentConfirmation): NoticeAdjustmentApplyResult =
        activator.withNoticeRepo { it.apply(confirmation) }

    override suspend fun history(limit: Int, beforeEventId: Long?): NoticeAdjustmentHistoryResult =
        activator.withNoticeRepo { it.history(limit, beforeEventId) }

    override suspend fun undo(eventId: Long, expectedRevision: Long): NoticeAdjustmentUndoResult =
        activator.withNoticeRepo { it.undo(eventId, expectedRevision) }
}
