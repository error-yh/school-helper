package com.aggapp.app.di

import android.content.Context
import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.data.RoomScheduleRepository
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.domain.repository.ScheduleRepository
import com.lingion.sleepy.domain.repository.ImportRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TimetableModule {
    @Provides
    @Singleton
    fun provideTimetableDatabase(@ApplicationContext context: Context): TimetableDatabase {
        val prefs = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
        val profileId = prefs.getString("id", null) ?: UUID.randomUUID().toString().also { id ->
            check(prefs.edit().putString("id", id).commit()) { "Unable to persist local timetable profile" }
        }
        return TimetableDatabase.open(context, profileId)
    }

    @Provides
    @Singleton
    fun provideRoomScheduleRepository(database: TimetableDatabase): RoomScheduleRepository =
        RoomScheduleRepository(database)

    @Provides fun provideScheduleRepository(repository: RoomScheduleRepository): ScheduleRepository = repository
    @Provides fun provideImportRepository(repository: RoomScheduleRepository): ImportRepository = repository

    @Provides
    @Singleton
    fun provideTimetableDependencies(repository: ScheduleRepository, imports: ImportRepository): TimetableDependencies =
        TimetableDependencies(repository, imports)
}
