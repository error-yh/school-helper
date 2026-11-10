/* SPDX-License-Identifier: GPL-3.0-only
 * Unambiguous canonical digest calculation for timetable backup payloads.
 * Strictly distinguishes NULL from string "null", escapes/lengths strings, and encodes full Schema 5 fields.
 */
package com.lingion.sleepy.data.backup

import java.security.MessageDigest

object TimetableBackupDigest {

    fun calculateDigest(payload: TimetableBackupPayloadDto): String {
        val md = MessageDigest.getInstance("SHA-256")
        val sb = StringBuilder(64 * 1024)

        // 1. Root metadata (excluding createdAt, sourceAppId, sourceVersionCode, contentDigest)
        sb.append("version=").appendField(payload.formatVersion.toLong())
        sb.append("roomSchema=").appendField(payload.roomSchemaVersion.toLong())
        sb.append("identityHash=").appendField(payload.schemaIdentityHash)
        sb.append("activeProfileId=").appendField(payload.activeProfileId)
        sb.append('\n')

        // 2. Profiles ordered by profileId
        val sortedProfiles = payload.profiles.sortedBy { it.profileId }
        sb.append("profilesCount=").appendField(sortedProfiles.size.toLong()).append('\n')

        for (p in sortedProfiles) {
            sb.append("profile=").appendField(p.profileId).append('\n')

            // 1. courses ordered by id
            sb.append("courses:")
            p.courses.sortedBy { it.id }.forEach { c ->
                sb.append('[')
                    .appendField(c.id)
                    .appendField(c.courseName)
                    .appendField(c.day.toLong())
                    .appendField(c.startNode.toLong())
                    .appendField(c.step.toLong())
                    .appendField(c.startWeek.toLong())
                    .appendField(c.endWeek.toLong())
                    .appendField(c.type.toLong())
                    .append(']')
            }
            sb.append('\n')

            // 2. semesters ordered by id
            sb.append("semesters:")
            p.semesters.sortedBy { it.id }.forEach { s ->
                sb.append('[')
                    .appendField(s.id)
                    .appendField(s.name)
                    .appendField(s.startEpochDay)
                    .appendField(s.totalWeeks.toLong())
                    .appendField(s.revision)
                    .append(']')
            }
            sb.append('\n')

            // 3. periods ordered by semesterId, number
            sb.append("periods:")
            p.periods.sortedWith(compareBy({ it.semesterId }, { it.number })).forEach { pr ->
                sb.append('[')
                    .appendField(pr.semesterId)
                    .appendField(pr.number.toLong())
                    .appendField(pr.startMinute.toLong())
                    .appendField(pr.endMinute.toLong())
                    .append(']')
            }
            sb.append('\n')

            // 4. scheduled_courses ordered by id
            sb.append("scheduled_courses:")
            p.scheduledCourses.sortedBy { it.id }.forEach { sc ->
                sb.append('[')
                    .appendField(sc.id)
                    .appendField(sc.semesterId)
                    .appendField(sc.courseName)
                    .appendField(sc.teacher)
                    .appendField(sc.room)
                    .appendField(sc.color)
                    .appendField(sc.sourceCourseCode)
                    .appendField(sc.sourceClassCode)
                    .append(']')
            }
            sb.append('\n')

            // 5. course_meetings ordered by id
            sb.append("course_meetings:")
            p.courseMeetings.sortedBy { it.id }.forEach { cm ->
                sb.append('[')
                    .appendField(cm.id)
                    .appendField(cm.courseId)
                    .appendField(cm.day.toLong())
                    .appendField(cm.customStartMinute?.toLong())
                    .appendField(cm.customEndMinute?.toLong())
                    .appendField(cm.teacher)
                    .appendField(cm.room)
                    .appendField(cm.kind)
                    .appendField(cm.experimentProject)
                    .appendField(cm.group)
                    .appendField(cm.isExperiment)
                    .append(']')
            }
            sb.append('\n')

            // 6. meeting_weeks ordered by meetingId, week
            sb.append("meeting_weeks:")
            p.meetingWeeks.sortedWith(compareBy({ it.meetingId }, { it.week })).forEach { mw ->
                sb.append('[')
                    .appendField(mw.meetingId)
                    .appendField(mw.week.toLong())
                    .append(']')
            }
            sb.append('\n')

            // 7. meeting_sections ordered by meetingId, section
            sb.append("meeting_sections:")
            p.meetingSections.sortedWith(compareBy({ it.meetingId }, { it.section })).forEach { ms ->
                sb.append('[')
                    .appendField(ms.meetingId)
                    .appendField(ms.section.toLong())
                    .append(']')
            }
            sb.append('\n')

            // 8. meeting_dates ordered by meetingId, epochDay
            sb.append("meeting_dates:")
            p.meetingDates.sortedWith(compareBy({ it.meetingId }, { it.epochDay })).forEach { mdEntry ->
                sb.append('[')
                    .appendField(mdEntry.meetingId)
                    .appendField(mdEntry.epochDay)
                    .append(']')
            }
            sb.append('\n')

            // 9. meeting_declared_weeks ordered by meetingId, week
            sb.append("meeting_declared_weeks:")
            p.meetingDeclaredWeeks.sortedWith(compareBy({ it.meetingId }, { it.week })).forEach { mdw ->
                sb.append('[')
                    .appendField(mdw.meetingId)
                    .appendField(mdw.week.toLong())
                    .append(']')
            }
            sb.append('\n')

            // 10. meeting_exceptions ordered by id
            sb.append("meeting_exceptions:")
            p.meetingExceptions.sortedBy { it.id }.forEach { me ->
                sb.append('[')
                    .appendField(me.id)
                    .appendField(me.meetingId)
                    .appendField(me.kind)
                    .appendField(me.originalEpochDay)
                    .appendField(me.targetEpochDay)
                    .appendField(me.customStartMinute?.toLong())
                    .appendField(me.customEndMinute?.toLong())
                    .appendField(me.hasSectionsOverride)
                    .appendField(me.teacher)
                    .appendField(me.room)
                    .appendField(me.experimentProject)
                    .appendField(me.group)
                    .append(']')
            }
            sb.append('\n')

            // 11. exception_sections ordered by exceptionId, section
            sb.append("exception_sections:")
            p.exceptionSections.sortedWith(compareBy({ it.exceptionId }, { it.section })).forEach { es ->
                sb.append('[')
                    .appendField(es.exceptionId)
                    .appendField(es.section.toLong())
                    .append(']')
            }
            sb.append('\n')

            // 12. schedule_provenance ordered by id
            sb.append("schedule_provenance:")
            p.scheduleProvenance.sortedBy { it.id }.forEach { sp ->
                sb.append('[')
                    .appendField(sp.id)
                    .appendField(sp.courseId)
                    .appendField(sp.meetingId)
                    .appendField(sp.exceptionId)
                    .appendField(sp.position.toLong())
                    .appendField(sp.sourceId)
                    .appendField(sp.adapterId)
                    .appendField(sp.page?.toLong())
                    .appendField(sp.left)
                    .appendField(sp.top)
                    .appendField(sp.right)
                    .appendField(sp.bottom)
                    .appendField(sp.rawText)
                    .appendField(sp.sourceLocator)
                    .append(']')
            }
            sb.append('\n')

            // 13. import_normalization_state ordered by courseId
            sb.append("import_normalization_state:")
            p.importNormalizationState.sortedBy { it.courseId }.forEach { ins ->
                sb.append('[')
                    .appendField(ins.courseId)
                    .appendField(ins.version.toLong())
                    .appendField(ins.payload)
                    .append(']')
            }
            sb.append('\n')

            // 14. notice_adjustment_events ordered by id
            sb.append("notice_adjustment_events:")
            p.noticeAdjustmentEvents.sortedBy { it.id }.forEach { nae ->
                sb.append('[')
                    .appendField(nae.id)
                    .appendField(nae.requestKey)
                    .appendField(nae.semanticKey)
                    .appendField(nae.confirmDigest)
                    .appendField(nae.scopeToken)
                    .appendField(nae.semesterId)
                    .appendField(nae.kind)
                    .appendField(nae.courseId)
                    .appendField(nae.courseTitle)
                    .appendField(nae.logicalBaseId)
                    .appendField(nae.structureFingerprint)
                    .appendField(nae.meetingId)
                    .appendField(nae.originalEpochDay)
                    .appendField(nae.targetEpochDay)
                    .appendField(nae.beforeJson)
                    .appendField(nae.afterJson)
                    .appendField(nae.afterFingerprint)
                    .appendField(nae.payloadJson)
                    .appendField(nae.sourceText)
                    .appendField(nae.sourceDigest)
                    .appendField(nae.dateBasisJson)
                    .appendField(nae.appliedRevision)
                    .appendField(nae.undoneRevision)
                    .appendField(nae.state)
                    .appendField(nae.activeKey)
                    .append(']')
            }
            sb.append('\n')

            // 15. sequences ordered by name
            sb.append("sequences:")
            p.sequences.sortedBy { it.name }.forEach { seq ->
                sb.append('[')
                    .appendField(seq.name)
                    .appendField(seq.seq)
                    .append(']')
            }
            sb.append('\n')

            // 16. displayGroups ordered by storageKey
            sb.append("displayGroups:")
            p.displayGroups.sortedBy { it.storageKey }.forEach { dg ->
                sb.append('[')
                    .appendField(dg.storageKey)
                    .appendField(dg.rawJson)
                    .append(']')
            }
            sb.append('\n')
        }

        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        val hash = md.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun StringBuilder.appendField(value: String?): StringBuilder {
        if (value == null) {
            append("N;")
        } else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            append("S").append(bytes.size).append(':').append(value).append(';')
        }
        return this
    }

    private fun StringBuilder.appendField(value: Long?): StringBuilder {
        if (value == null) append("N;") else append("I").append(value).append(';')
        return this
    }

    private fun StringBuilder.appendField(value: Boolean): StringBuilder {
        append("B").append(if (value) '1' else '0').append(';')
        return this
    }

    private fun StringBuilder.appendField(value: Double?): StringBuilder {
        if (value == null) append("N;") else append("F").append(value.toRawBits()).append(';')
        return this
    }
}
