/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.model.TimeRange

/** Local half-open interval arithmetic. No weeks/sections Cartesian reconstruction. */
internal object ImportResidualBuilder {
    fun subtract(range:TimeRange, cuts:List<TimeRange>):List<TimeRange> {
        require(cuts.size <= 200_000) { "OCCURRENCE_LIMIT" }
        var cursor = range.start
        val result = mutableListOf<TimeRange>()
        for (cut in cuts.sortedWith(compareBy<TimeRange> { it.start }.thenBy { it.end })) {
            if (cut.end <= cursor || cut.start >= range.end) continue
            if (cut.start > cursor) result += TimeRange(cursor,minOf(cut.start,range.end))
            cursor = maxOf(cursor,cut.end)
            if (cursor >= range.end) break
        }
        if (cursor < range.end) result += TimeRange(cursor,range.end)
        return fList(result)
    }

    fun intersection(a:TimeRange,b:TimeRange):TimeRange? {
        val start=maxOf(a.start,b.start);val end=minOf(a.end,b.end)
        return if(start<end) TimeRange(start,end) else null
    }

    fun union(ranges:List<TimeRange>):List<TimeRange> {
        val result=mutableListOf<TimeRange>()
        for(range in ranges.sortedWith(compareBy<TimeRange>{it.start}.thenBy{it.end})) {
            val last=result.lastOrNull()
            if(last!=null && range.start<=last.end) result[result.lastIndex]=TimeRange(last.start,maxOf(last.end,range.end))
            else result+=range
        }
        return fList(result)
    }
}
