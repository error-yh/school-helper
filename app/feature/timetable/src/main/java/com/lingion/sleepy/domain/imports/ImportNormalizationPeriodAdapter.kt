/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.fList
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimetableSnapshot

internal object ImportNormalizationPeriodAdapter {
    /**
     * Adapts a stored normalization state to new timetable periods when period timings change.
     * Preserves:
     * - Course, meeting and exception IDs
     * - FamilyId and logical base stability
     * - Explicit customTime arrangements and fixed source periods
     * - Historical provenance and effect groups
     * Validates the resulting state against the candidate snapshot.
     */
    fun adapt(
        state: StoredNormalizationState,
        oldPeriods: List<Period>,
        newPeriods: List<Period>,
        course: ScheduledCourse,
        candidateSnapshot: TimetableSnapshot,
    ): StoredNormalizationState {
        if (oldPeriods == newPeriods) {
            ImportNormalizationCodec.validate(
                state,
                candidateSnapshot.scopeToken,
                candidateSnapshot.settings.id,
                course,
                candidateSnapshot
            )
            return state
        }

        val oldOriginals = state.graph.originals.associateBy { it.familyId }

        // 1. Adapt originals
        val newOriginals = state.graph.originals.map { original ->
            if (!original.sectionsUseTarget || original.original.customTime != null) {
                // Arrangements with explicit customTime or fixed source periods do not use system periods.
                original
            } else {
                val newIntervals = TimetableRules.periodIntervals(original.original, newPeriods)
                require(newIntervals.size == original.intervals.size) {
                    "作息节次数量与课程已有节次不匹配"
                }
                val intervalMap = original.intervals.indices.associate { index ->
                    original.intervals[index] to newIntervals[index]
                }
                val newAllocated = original.allocated.map { dated ->
                    val mappedRange = intervalMap[dated.range]
                        ?: throw InvalidNormalizationState("ALLOCATION_INTERVAL_MISMATCH")
                    DatedInterval(dated.date, mappedRange)
                }
                val newDescriptor = ImportOccurrencePlanner.descriptorFingerprint(
                    original.original,
                    original.identity,
                    newIntervals,
                    true
                )
                original.copy(
                    descriptorFingerprint = newDescriptor,
                    intervals = fList(newIntervals),
                    allocated = fList(newAllocated),
                )
            }
        }
        val newOriginalsByFamily = newOriginals.associateBy { it.familyId }

        // 2. Adapt removes (cancellations of target sections)
        val newRemoves = state.graph.removes.map { remove ->
            val oldOriginal = oldOriginals[remove.familyId]
                ?: throw InvalidNormalizationState("REMOVE_FAMILY_MISSING")
            if (!oldOriginal.sectionsUseTarget || oldOriginal.original.customTime != null) {
                remove
            } else {
                val newOriginal = newOriginalsByFamily.getValue(remove.familyId)
                val index = oldOriginal.intervals.indexOf(remove.scope.range)
                val newRange = if (index >= 0 && index < newOriginal.intervals.size) {
                    newOriginal.intervals[index]
                } else {
                    throw InvalidNormalizationState("REMOVE_RANGE_MISMATCH")
                }
                remove.copy(scope = DatedInterval(remove.scope.date, newRange))
            }
        }

        // 3. Assemble adapted state
        val adapted = state.copy(
            graph = state.graph.copy(
                originals = fList(newOriginals),
                removes = fList(newRemoves),
            )
        )

        // 4. Strictly validate invariants under candidate snapshot
        ImportNormalizationCodec.validate(
            adapted,
            candidateSnapshot.scopeToken,
            candidateSnapshot.settings.id,
            course,
            candidateSnapshot
        )

        return adapted
    }
}
