/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import com.lingion.sleepy.importing.*
import java.time.DateTimeException
import java.time.LocalDate

/** Bounded, conservative notice recognizer. All inferred dates and attachments require review. */
class NoticeTimetableParser {
    fun parse(text:String,context:NoticeParseContext):FormatParseResult = try {
        Decoder(text,context).decode()
    } catch(failure:ImportRejected) { FormatParseResult(null,issues=listOf(publicIssue(failure.code))) }

    private class Decoder(val text:String,val context:NoticeParseContext) {
        val format=context.formatContext
        val sources=mutableListOf<ImportSourceRef>()
        val relative=mutableListOf<RelativeDateProposal>()
        val pending=mutableListOf<PendingExceptionAttachment>()
        val unparsed=mutableListOf<ImportSourceRef>()
        val issues=mutableListOf<ParseIssue>()
        val courses=mutableListOf<RichCourseDraft>()

        fun decode():FormatParseResult {
            format.cancellation.check()
            if(text.length>format.limits.maxTextUnits || text.toByteArray(Charsets.UTF_8).size>format.limits.maxInputBytes) reject("LIMIT_EXCEEDED")
            var start=0; var number=0
            for(i in 0..text.length) {
                format.cancellation.check()
                if(i==text.length || text[i] in "\n;；") {
                    val raw=text.substring(start,i)
                    start=i+1
                    if(raw.isBlank()) continue
                    if(++number>format.limits.maxRows || raw.length>8192 || raw.length>format.limits.maxFieldUnits) reject("LIMIT_EXCEEDED")
                    clause(raw,number)
                }
            }
            return FormatParseResult(RichTimetableDraft(courses=courses.toList(),issues=issues.toList(),adapterIds=setOf("notice-explicit-basis")),sources.toList(),issues.toList(),pendingExceptions=pending.toList(),relativeDates=relative.toList(),unparsedSources=unparsed.toList())
        }

        fun clause(raw:String,number:Int) {
            format.cancellation.check()
            val source=ImportSourceRef(format.sourceId,"notice:clause:$number",raw,DraftPath())
            sources+=source
            val consumed=BooleanArray(raw.length)
            fun consume(range:IntRange) { for(i in range) { format.cancellation.check(); consumed[i]=true } }
            fun matches(regex:Regex):List<MatchResult> = regex.findAll(raw).toList().also { format.cancellation.check() }
            fun firstLabel(label:String):String? {
                val found=matches(Regex("(?:$label)\\s*[:：]\\s*([^，,\\s]+)"))
                found.forEach { consume(it.range) }
                if(found.size>1) issues+=mappingIssue("AMBIGUOUS_NOTICE_LABEL")
                return found.firstOrNull()?.groupValues?.get(1)
            }
            val teacher=firstLabel("教师|老师|teacher")
            val group=firstLabel("班级|组别|group")
            val project=firstLabel("实验项目|项目|project")
            var room=firstLabel("教室|实验室|地点|room")
            val explicitCourse=firstLabel("课程名称|课程|course")
            if(room==null) {
                val places=matches(Regex("在\\s*([^，,\\s]+?)(?=加课|停课|取消|增加|调至|$|[，,\\s])"))
                places.forEach { consume(it.range) }
                room=places.firstOrNull()?.groupValues?.get(1)
                if(places.size>1) issues+=mappingIssue("AMBIGUOUS_NOTICE_LABEL")
            }
            // Room roles are consumed before operation verbs: "教室改为B" is a location change,
            // not a reschedule verb, and "教室不变" is an explicit keep rather than silence.
            var roomKeep=false
            var roomFrom:String?=null
            var roomTo:String?=null
            matches(Regex("(?:原)?(?:教室|地点|实验室)(?:保持)?不变")).forEach { consume(it.range);roomKeep=true }
            val roomRangeMatches=matches(Regex("(?:教室|地点|实验室)\\s*由\\s*([^，,。;；\\s]+?)\\s*(?:改为|调整为|调至|改至|变更为)\\s*([^，,。;；\\s]+)"))
            roomRangeMatches.firstOrNull()?.let { first->
                roomFrom=first.groupValues[1];roomTo=first.groupValues[2]
                roomRangeMatches.forEach { consume(it.range) }
            }
            if(roomTo==null) {
                val roomToMatches=matches(Regex("(?:教室|地点|实验室)\\s*(?:改为|调整为|调至|改至|变更为)\\s*([^，,。;；\\s]+)"))
                roomToMatches.firstOrNull()?.let { first->
                    roomTo=first.groupValues[1]
                    roomToMatches.forEach { consume(it.range) }
                }
            }
            if(roomTo!=null) room=roomTo else if(roomKeep) room=null
            // "教室不变" and "教室改为X" contradict each other and stay unresolved.
            if(roomKeep && roomTo!=null) { issues+=mappingIssue("AMBIGUOUS_NOTICE_LABEL");roomKeep=false;roomTo=null;room=null }
            // 上午/下午第N大节 states a day part; it is not a clock and is never two guessed periods.
            val bigMatch=matches(Regex("(上午|下午)\\s*(?:第?\\s*([一二三四1-4])\\s*大节)")).singleOrNull()
            val bigSection=bigMatch?.let { m->
                val ordinal=when(m.groupValues[2]) { "一"->1;"二"->2;"三"->3;"四"->4;else->m.groupValues[2].toInt() }
                NoticeBigSection(if(m.groupValues[1]=="上午") NoticeDayPart.MORNING else NoticeDayPart.AFTERNOON,ordinal)
            }
            bigMatch?.let { consume(it.range) }
            val bigRange=bigMatch?.range
            // Operation verbs. "调整为"/"改为" are part of the user's own wording and were missing.
            val rescheduleVerbs=setOf("调整为","调整至","调至","改至","改为")
            val markerMatches=matches(Regex("调整为|调整至|调至|改至|改为|补课|加课|增加|新增|停课|取消"))
                .filterNot { consumed[it.range.first] }
            markerMatches.forEach { consume(it.range) }
            val rescheduleMarkers=markerMatches.filter { it.value in rescheduleVerbs }
            val marker=rescheduleMarkers.singleOrNull()
            val kinds=markerMatches.map { when(it.value) {
                in rescheduleVerbs -> RichExceptionKind.RESCHEDULE
                "停课","取消" -> RichExceptionKind.CANCEL
                else -> RichExceptionKind.EXTRA
            } }.distinct()
            if(kinds.size>1) issues+=mappingIssue("AMBIGUOUS_NOTICE_OPERATION")
            var operation=kinds.singleOrNull()
            // A standalone "教室改为B" is a one-occurrence location change even without a verb.
            if(operation==null && roomTo!=null) operation=RichExceptionKind.RESCHEDULE
            if(operation==RichExceptionKind.RESCHEDULE) {
                matches(Regex("由原来的|由原本的|原来的|原本的")).forEach { consume(it.range) }
            }
            fun beforeMarker(range:IntRange)=marker!=null && range.last<marker.range.first
            fun afterMarker(range:IntRange)=marker!=null && range.first>marker.range.last
            // At most one seconds component is lexical clock syntax; colon boundaries prevent
            // an unsupported longer token from exposing a shorter valid-looking clock suffix.
            val timeMatches=matches(Regex("(?<![0-9:])\\d+:\\d+(?::\\d+)?\\s*[-~～至]\\s*\\d+:\\d+(?::\\d+)?(?![0-9:])"))
            val fullDates=matches(Regex("(?<![0-9-])\\d+-\\d+-\\d+(?![0-9-])"))
            val dateOrClock=BooleanArray(raw.length)
            for(m in timeMatches+fullDates) for(i in m.range) dateOrClock[i]=true
            data class NumericDeclaration(val range:IntRange,val value:String)
            // Scan once, retaining the whole declaration even when its grammar is unsupported.
            // Unlike nested regex repetitions, long sparse lists cannot exhaust the call stack.
            fun numericDeclarations(unit:Char):List<NumericDeclaration> {
                val found=mutableListOf<NumericDeclaration>()
                val syntax="0123456789第、，,至-~～—－/+. \t\r"
                val separators="、，,至-~～—－/+."
                var i=0
                while(i<raw.length) {
                    format.cancellation.check()
                    // "调整至/调至/改至" end with a range separator; the following "第N-M节" is a
                    // real declaration, not the tail of a range.
                    val afterRescheduleVerb = i >= 2 && raw[i-1] == '至' && raw[i-2] in "调整改"
                    val blockedByPrevious = i>0 && raw[i-1] in "0123456789第:/+.至-~～—－" && !afterRescheduleVerb
                    if(dateOrClock[i] || raw[i] !in "0123456789第+-" || blockedByPrevious) { i++; continue }
                    val start=i
                    var end:Int?=null
                    while(i<raw.length && !dateOrClock[i]) {
                        format.cancellation.check()
                        if(raw[i]==unit) {
                            i++
                            if(unit=='周' && i<raw.length && raw[i] in "(（") {
                                i++
                                while(i<raw.length && raw[i] in "单双周)） \t\r") {
                                    val closing=raw[i] in ")）"
                                    i++
                                    if(closing) break
                                }
                            }
                            end=i
                            var next=i
                            while(next<raw.length && raw[next].isWhitespace()) next++
                            if(next>=raw.length || raw[next] !in separators) break
                            while(next<raw.length && (raw[next] in separators || raw[next].isWhitespace())) next++
                            if(next>=raw.length || raw[next] !in "0123456789第" || dateOrClock[next]) break
                            // A comma can separate independent week and section declarations.
                            // Inspect the continuation's unit before taking any of its prefix.
                            var continuation=next
                            while(continuation<raw.length && !dateOrClock[continuation] && raw[continuation] in syntax) {
                                format.cancellation.check()
                                continuation++
                            }
                            if(continuation<raw.length && raw[continuation] in "周节" && raw[continuation]!=unit) break
                        } else if(raw[i] in syntax) i++ else break
                    }
                    if(end!=null) {
                        // Include any malformed numeric continuation after the last unit as well.
                        val stop=if(i>end) i else end
                        found+=NumericDeclaration(start until stop,raw.substring(start,stop))
                    }
                    if(i==start) i++
                }
                return found
            }
            val weekMatches=numericDeclarations('周')
                .filter { match-> match.range.first==0 || raw[match.range.first-1]!='下' && raw[match.range.first-1]!='本' }
            val weeks=linkedSetOf<Int>()
            for(m in weekMatches) {
                format.cancellation.check(); consume(m.range)
                val parsed=WeekExpressionParser.parse(m.value,format.maxWeek)
                if(parsed.errors.isNotEmpty()) issues+=mappingIssue("INVALID_WEEKS") else weeks+=parsed.weeks
            }
            var originalSectionsInvalid=false
            var targetSectionsInvalid=false
            val sectionDeclarations=numericDeclarations('节').map { match->
                format.cancellation.check();consume(match.range)
                val parsed=WeekExpressionParser.parse(match.value.removeSuffix("节"),100)
                if(parsed.errors.isNotEmpty()) {
                    issues+=mappingIssue("INVALID_SECTIONS")
                    // The malformed declaration keeps its lexical side so the role can be corrected.
                    if(marker!=null) { if(beforeMarker(match.range)) originalSectionsInvalid=true else targetSectionsInvalid=true }
                    else if(operation==RichExceptionKind.EXTRA) targetSectionsInvalid=true else originalSectionsInvalid=true
                    match.range to emptySet<Int>()
                } else match.range to parsed.weeks
            }
            val allSections=sectionDeclarations.flatMap { it.second }.toSet()
            // Old sections are a match condition; target sections are the override. A one-occurrence
            // room/date change without a reschedule verb keeps the declared sections as the old scope.
            val originalSections=when {
                marker!=null -> sectionDeclarations.filter { beforeMarker(it.first) }.flatMap { it.second }.toSet()
                operation==RichExceptionKind.CANCEL || operation==RichExceptionKind.RESCHEDULE -> allSections
                else -> emptySet()
            }
            val targetSections=when {
                marker!=null -> sectionDeclarations.filter { afterMarker(it.first) }.flatMap { it.second }.toSet()
                operation==RichExceptionKind.EXTRA -> allSections
                else -> emptySet()
            }
            val sections=when(operation) { RichExceptionKind.RESCHEDULE -> targetSections; else -> allSections }
            var originalTimeInvalid=false
            var targetTimeInvalid=false
            val parsedTimes=timeMatches.map { match->
                consume(match.range)
                val pair=match.value.split(Regex("\\s*[-~～至]\\s*"))
                val a=exactTime(pair[0]); val b=exactTime(pair[1])
                if(a!=null && b!=null && b>a) match.range to DraftTimeRange(a,b)
                else {
                    issues+=mappingIssue("INVALID_TIME")
                    if(marker!=null) { if(beforeMarker(match.range)) originalTimeInvalid=true else targetTimeInvalid=true }
                    else if(operation==RichExceptionKind.EXTRA) targetTimeInvalid=true else originalTimeInvalid=true
                    match.range to null
                }
            }
            val originalTimes=when {
                marker!=null -> parsedTimes.filter { beforeMarker(it.first) }.mapNotNull { it.second }
                operation==RichExceptionKind.CANCEL || operation==RichExceptionKind.RESCHEDULE -> parsedTimes.mapNotNull { it.second }
                else -> emptyList()
            }
            val targetTimes=when {
                marker!=null -> parsedTimes.filter { afterMarker(it.first) }.mapNotNull { it.second }
                operation==RichExceptionKind.EXTRA -> parsedTimes.mapNotNull { it.second }
                else -> emptyList()
            }
            val allTimes=parsedTimes.mapNotNull { it.second }
            if(originalTimes.size>1 || targetTimes.size>1) issues+=mappingIssue("AMBIGUOUS_TIME_MAPPING")
            val time=when(operation) {
                // Only the target time overrides; an old time is a match condition, never an override.
                RichExceptionKind.RESCHEDULE -> if(targetTimes.size==1) targetTimes.single() else null
                else -> if(allTimes.size==1) allTimes.single()
                    else { if(allTimes.size>1) issues+=mappingIssue("AMBIGUOUS_TIME_MAPPING");null }
            }

            // Null slots keep invalid declarations in their original/target lexical positions.
            val dateDeclarations=fullDates.map { m ->
                consume(m.range)
                m.range to exactDate(m.value).also { if(it==null) issues+=mappingIssue("INVALID_DATE") }
            }.toMutableList()
            val dates=dateDeclarations.mapNotNull { it.second }
            val dayMatches=matches(Regex("(?:星期|周)([一二三四五六日天])"))
            val declaredDay=dayMatches.firstOrNull()?.groupValues?.get(1)?.let { "一二三四五六日".indexOf(if(it=="天") "日" else it)+1 }
            dayMatches.forEach { consume(it.range) }
            if(dayMatches.map { it.groupValues[1] }.distinct().size>1) issues+=mappingIssue("AMBIGUOUS_DAY_OF_WEEK")
            if(declaredDay!=null && dates.any { it.dayOfWeek.value!=declaredDay }) issues+=mappingIssue("DATE_WEEKDAY_MISMATCH")
            val relativeMatches=matches(Regex("今日|今天|明日|明天|后天|(?:本周|下周)[一二三四五六日天]?|周末|近期|下次"))
            var relativeDate:LocalDate?=null
            for(m in relativeMatches) {
                consume(m.range)
                val p=proposal(m.value,source)
                relative+=p
                dateDeclarations+=m.range to p.candidate
                if(relativeDate==null) relativeDate=p.candidate else issues+=mappingIssue("AMBIGUOUS_RELATIVE_DATE")
            }
            val monthDays=matches(Regex("(?<![0-9])([0-9]{1,2})月([0-9]{1,2})日"))
            for(m in monthDays) {
                consume(m.range)
                val month=m.groupValues[1].toInt(); val day=m.groupValues[2].toInt()
                val anchor=context.confirmedTermMonday
                val year=context.confirmedYear ?: anchor?.let { it.year+if(month<it.monthValue || month==it.monthValue && day<it.dayOfMonth) 1 else 0 }
                val candidate=if(year==null) null else safeDate { LocalDate.of(year,month,day) }
                val rule=if(context.confirmedYear!=null) "EXPLICIT_YEAR_MONTH_DAY" else "CONFIRMED_TERM_NEXT_MONTH_DAY"
                relative+=RelativeDateProposal(m.value,source,if(context.confirmedYear==null) anchor ?: context.referenceDate else context.referenceDate,candidate,rule,if(year==null) "EXPLICIT_YEAR_OR_TERM_REQUIRED" else if(candidate==null) "INVALID_DATE" else null,context.referenceDate,anchor,context.confirmedYear)
                dateDeclarations+=m.range to candidate
                if(candidate!=null && anchor!=null && candidate.toEpochDay()-anchor.toEpochDay() !in 0 until format.maxWeek.toLong()*7) issues+=mappingIssue("DATE_OUTSIDE_CONFIRMED_TERM")
                if(relativeDate==null) relativeDate=candidate
            }
            if(relativeMatches.size+monthDays.size>1) {
                relativeDate=null
                issues+=mappingIssue("AMBIGUOUS_RELATIVE_DATE")
            }
            if(relativeMatches.isEmpty() && monthDays.isEmpty() && fullDates.isEmpty() && weeks.size==1 && declaredDay!=null) {
                val anchor=context.confirmedTermMonday
                val candidate=anchor?.let { safeDate { it.plusWeeks((weeks.single()-1).toLong()).plusDays((declaredDay-1).toLong()) } }
                val token=weekMatches.joinToString("") { it.value }+dayMatches.joinToString("") { it.value }
                relative+=RelativeDateProposal(token,source,anchor ?: context.referenceDate,candidate,"CONFIRMED_TERM_WEEK_DAY",if(anchor==null) "CONFIRMED_TERM_MONDAY_REQUIRED" else if(candidate==null) "DATE_OVERFLOW" else null,context.referenceDate,anchor,context.confirmedYear)
                relativeDate=candidate
            }
            if(relative.any { it.source==source }) issues+=mappingIssue("RELATIVE_DATE_REVIEW")
            if(declaredDay!=null && relativeDate!=null && relativeDate.dayOfWeek.value!=declaredDay) issues+=mappingIssue("DATE_WEEKDAY_MISMATCH")
            val originalDates=marker?.let { currentMarker -> dateDeclarations.filter { it.first.last<currentMarker.range.first } }.orEmpty()
            val targetDates=marker?.let { currentMarker -> dateDeclarations.filter { it.first.first>currentMarker.range.last } }.orEmpty()
            val ambiguousDates=when(operation) {
                RichExceptionKind.RESCHEDULE -> if(marker==null) dateDeclarations.size>1
                    else rescheduleMarkers.size!=1 || originalDates.size>1 || targetDates.size>1
                RichExceptionKind.CANCEL,RichExceptionKind.EXTRA -> dateDeclarations.size>1
                null -> false
            }
            if(ambiguousDates) issues+=mappingIssue("AMBIGUOUS_NOTICE_DATES")
            // Prefix is an explicit old-date marker, never part of a course title.
            if(raw.startsWith("原") && dateDeclarations.any { it.first.first==1 }) consumed[0]=true
            val fragments=mutableListOf<String>()
            var current=StringBuilder()
            fun flush() { if(current.isNotEmpty()) { fragments+=current.toString(); current=StringBuilder() } }
            for(i in raw.indices) {
                format.cancellation.check()
                if(consumed[i] || raw[i] in "，, \t\r") flush() else current.append(raw[i])
            }
            flush()
            val uncertainty=setOf("上午","下午","晚上","中午","傍晚")
            val candidateName=fragments.firstOrNull { it !in uncertainty && !it.contains("另行通知") && !it.contains("安排") }.orEmpty()
            val name=explicitCourse ?: cleanNoticeName(candidateName)
            val remains=fragments.filter { it!=(if(explicitCourse==null) candidateName else name) || explicitCourse!=null }
            if(remains.isNotEmpty()) unparsed+=source.copy(rawText=remains.joinToString(" "))
            if(uncertainty.any { raw.contains(it) }) issues+=mappingIssue("UNRESOLVED_TIME_OF_DAY")
            if(operation!=null) {
                if(pending.size>=format.limits.maxExceptions) reject("LIMIT_EXCEEDED")
                val original=if(ambiguousDates) null else when(operation) {
                    RichExceptionKind.CANCEL -> if(dateDeclarations.isEmpty()) relativeDate else dateDeclarations.single().second
                    // A room-only change has no reschedule verb; its declared date is the original one.
                    RichExceptionKind.RESCHEDULE -> if(marker==null) (if(dateDeclarations.isEmpty()) relativeDate else dateDeclarations.singleOrNull()?.second)
                        else originalDates.singleOrNull()?.second
                    RichExceptionKind.EXTRA -> null
                }
                val target=if(ambiguousDates) null else when(operation) { RichExceptionKind.EXTRA -> if(dateDeclarations.isEmpty()) relativeDate else dateDeclarations.single().second; RichExceptionKind.RESCHEDULE -> targetDates.singleOrNull()?.second; RichExceptionKind.CANCEL -> null }
                // A same-day time/section/room change keeps the original date as its target date.
                val sameDayTarget=operation==RichExceptionKind.RESCHEDULE && original!=null && target==null && !ambiguousDates &&
                    (targetSections.isNotEmpty() || targetTimes.isNotEmpty() || roomTo!=null)
                val resolvedTarget=target ?: if(sameDayTarget) original else null
                val missing=buildSet { if(operation!=RichExceptionKind.EXTRA && original==null) add(ImportField.ORIGINAL_DATE); if(operation!=RichExceptionKind.CANCEL && resolvedTarget==null) add(ImportField.TARGET_DATE); if(name.isBlank()) add(ImportField.COURSE_NAME) }
                val draftSections=when(operation) {
                    RichExceptionKind.RESCHEDULE -> targetSections.takeIf { it.isNotEmpty() }
                    else -> allSections.takeIf { it.isNotEmpty() }
                }
                val draftRoom=when(operation) { RichExceptionKind.CANCEL -> room; else -> roomTo ?: if(roomKeep) null else room }
                val intent=when(operation) {
                    RichExceptionKind.CANCEL -> NoticeIntentKind.CANCEL
                    RichExceptionKind.EXTRA -> NoticeIntentKind.EXTRA
                    RichExceptionKind.RESCHEDULE -> when {
                        resolvedTarget!=null && original!=null && resolvedTarget!=original -> NoticeIntentKind.CHANGE_DATE
                        roomTo!=null && targetSections.isEmpty() && targetTimes.isEmpty() -> NoticeIntentKind.ROOM_ONLY
                        else -> NoticeIntentKind.RESCHEDULE_TIME
                    }
                }
                val roles=NoticeExceptionRoles(
                    intent=intent,
                    original=NoticeScope(originalSections, originalTimes.singleOrNull()),
                    target=NoticeScope(targetSections, targetTimes.singleOrNull()),
                    room=when { roomTo!=null -> NoticeRoomChange(NoticeRoomRole.CHANGE, roomFrom ?: room, roomTo); roomKeep -> NoticeRoomChange(NoticeRoomRole.KEEP); else -> null },
                    bigSection=bigSection?.copy(originalSide = bigRange!=null && marker!=null && bigRange.last<marker.range.first),
                    sameDayAdjustment=sameDayTarget,
                    originalTimeInvalid=originalTimeInvalid,
                    originalSectionsInvalid=originalSectionsInvalid,
                    targetTimeInvalid=targetTimeInvalid,
                    targetSectionsInvalid=targetSectionsInvalid,
                )
                val e=RichExceptionDraft(operation,original,resolvedTarget,time,draftSections,teacher,draftRoom,project,group,warnings=listOf(mappingIssue("PENDING_EXCEPTION_ATTACHMENT")),missingFields=missing)
                pending+=PendingExceptionAttachment(exception=e,sources=listOf(source),reason="EXPLICIT_ATTACHMENT_REQUIRED",courseName=name.takeIf { it.isNotBlank() },teacher=teacher,room=room,group=group,experimentProject=project,declaredWeeks=weeks.toSet(),dayOfWeek=declaredDay,roles=roles)
                issues+=mappingIssue("PENDING_EXCEPTION_ATTACHMENT")
            } else if(kinds.isEmpty() && name.isNotBlank() && (weeks.isNotEmpty() || dates.isNotEmpty()) && (sections.isNotEmpty() || time!=null)) {
                if(courses.size>=format.limits.maxCourses || courses.size>=format.limits.maxMeetings) reject("LIMIT_EXCEEDED")
                val kind=if(dates.isNotEmpty()) MeetingKind.DATE_SPECIFIC else if(declaredDay!=null && weeks.isNotEmpty()) MeetingKind.RECURRING else MeetingKind.UNSCHEDULED
                courses+=RichCourseDraft(name,meetings=listOf(RichMeetingDraft(kind,declaredDay,weeks.toSet(),sections.toSet(),time,dates.toSet(),teacher,room,group,project,name.contains("实验"),warnings=listOf(mappingIssue("NOTICE_REVIEW_REQUIRED")))))
                issues+=mappingIssue("NOTICE_REVIEW_REQUIRED")
            } else { if(unparsed.none { it.sourceLocator==source.sourceLocator && it.rawText==raw }) unparsed+=source; issues+=mappingIssue("UNPARSED_NOTICE") }
        }

        /** The unlabeled title is only a lookup hint; drop connective "的" and a trailing "课程". */
        private fun cleanNoticeName(value:String):String {
            var name=value.trim()
            if(name.startsWith("的")) name=name.drop(1)
            name=name.removeSuffix("课程").trim()
            return name.ifBlank { value.trim() }
        }

        fun safeDate(block:()->LocalDate):LocalDate? = try {
            block().takeIf { it.year in 1..9999 } ?: run { issues+=mappingIssue("DATE_OVERFLOW"); null }
        } catch(_:DateTimeException) { issues+=mappingIssue("DATE_OVERFLOW"); null } catch(_:ArithmeticException) { issues+=mappingIssue("DATE_OVERFLOW"); null }

        fun proposal(token:String,source:ImportSourceRef):RelativeDateProposal {
            val reference=context.referenceDate
            val candidate:LocalDate?
            val rule:String
            var unresolved:String?=null
            when(token) {
                "今日","今天","明日","明天","后天" -> {
                    rule="REFERENCE_DAY_OFFSET"
                    val offset=when(token) { "今日","今天"->0L; "明日","明天"->1L; else->2L }
                    candidate=safeDate { reference.plusDays(offset) }
                }
                else -> if(token.startsWith("本周") || token.startsWith("下周")) {
                    rule="REFERENCE_ISO_WEEK_DAY"
                    val day=token.drop(2).let { "一二三四五六日".indexOf(if(it=="天") "日" else it)+1 }
                    candidate=if(day !in 1..7 || token.length!=3) { unresolved="EXPLICIT_WEEKDAY_REQUIRED"; null } else safeDate { reference.minusDays((reference.dayOfWeek.value-1).toLong()).plusWeeks(if(token.startsWith("下")) 1 else 0).plusDays((day-1).toLong()) }
                } else { rule="UNRESOLVED_RELATIVE"; candidate=null; unresolved="RELATIVE_TOKEN_HAS_NO_EXACT_DATE" }
            }
            if(candidate==null && unresolved==null) unresolved="DATE_OVERFLOW"
            return RelativeDateProposal(token,source,reference,candidate,rule,unresolved,reference,context.confirmedTermMonday,context.confirmedYear)
        }
    }
}
