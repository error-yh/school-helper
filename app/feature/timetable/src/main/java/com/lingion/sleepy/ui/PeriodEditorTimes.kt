/* SPDX-License-Identifier: GPL-3.0-only
 * Minute arithmetic adapted from Sleepy contributors, frozen
 * 3dafa110f74ac4f32464ce1efddb24197a4091d9, SmartPeriodConfig.kt:88-108.
 * Keep manually chosen starts/breaks; reject crossing midnight rather than wrapping.
 */
package com.lingion.sleepy.ui
import com.lingion.sleepy.domain.model.Period
import java.time.LocalTime
internal object PeriodEditorTimes {
    fun endAt(start:LocalTime,minutes:Int):LocalTime {
        require(minutes in 1..1439) {"课时时长请填写1到1439分钟。"}
        val end=start.hour*60+start.minute+minutes
        require(end<24*60) {"本节结束时间超出当天，请缩短时长或修改开始时间。"}
        return LocalTime.of(end/60,end%60)
    }
    fun withDuration(periods:List<Period>,minutes:Int):List<Period> =
        periods.map {it.copy(end=endAt(it.start,minutes))}
}
