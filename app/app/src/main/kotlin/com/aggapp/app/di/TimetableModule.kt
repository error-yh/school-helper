package com.aggapp.app.di

import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.domain.repository.ScheduleRepository
import com.lingion.sleepy.domain.repository.ImportRepository
import com.lingion.sleepy.domain.repository.NoticeAdjustmentRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TimetableModule {
    @Provides
    @Singleton
    fun provideScheduleRepository(activator: TimetableProfileActivator): ScheduleRepository =
        activator.scheduleRepository

    @Provides
    fun provideImportRepository(activator: TimetableProfileActivator): ImportRepository =
        activator.importRepository

    @Provides
    @Singleton
    fun provideNoticeAdjustmentRepository(activator: TimetableProfileActivator): NoticeAdjustmentRepository =
        activator.noticeAdjustmentRepository

    @Provides
    @Singleton
    fun provideTimetableDependencies(
        repository: ScheduleRepository,
        imports: ImportRepository,
        noticeAdjustments: NoticeAdjustmentRepository,
    ): TimetableDependencies =
        TimetableDependencies(repository, imports, noticeAdjustments)
}
