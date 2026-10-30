package com.lingion.sleepy.data

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.GraphicsMode

/** Explicit optional source set: -PrunCalendarRoomJvm=true. Device contract is unconditional. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RoomCalendarRuntimeTest : CalendarRoomContractTest()
