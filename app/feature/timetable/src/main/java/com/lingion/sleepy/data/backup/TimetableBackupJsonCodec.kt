/* SPDX-License-Identifier: GPL-3.0-only
 * Strict JSON codec with bounds enforcement, duplicate key rejection, zero unknown field tolerance,
 * and strict type assertions (no string-to-number coercion, mandatory table arrays).
 */
package com.lingion.sleepy.data.backup

import kotlinx.serialization.json.*

object TimetableBackupJsonCodec {

    fun encode(payload: TimetableBackupPayloadDto): String {
        require(payload.profiles.size <= TIMETABLE_BACKUP_MAX_PROFILES) {
            "Profile数量超过最大限制: ${payload.profiles.size} > $TIMETABLE_BACKUP_MAX_PROFILES"
        }
        val totalRows = payload.totalRowCount()
        require(totalRows <= TIMETABLE_BACKUP_MAX_TOTAL_ROWS) {
            "总表行数超过最大限制: $totalRows > $TIMETABLE_BACKUP_MAX_TOTAL_ROWS"
        }

        val jsonObject = buildJsonObject {
            put("formatVersion", payload.formatVersion)
            put("roomSchemaVersion", payload.roomSchemaVersion)
            put("schemaIdentityHash", payload.schemaIdentityHash)
            put("sourceAppId", payload.sourceAppId)
            put("sourceVersionCode", payload.sourceVersionCode)
            put("createdAt", payload.createdAt)
            put("activeProfileId", payload.activeProfileId)
            put("contentDigest", payload.contentDigest)
            putJsonArray("profiles") {
                payload.profiles.forEach { profile ->
                    add(encodeProfile(profile))
                }
            }
        }

        val jsonString = jsonObject.toString()
        val byteLength = jsonString.toByteArray(Charsets.UTF_8).size
        require(byteLength <= TIMETABLE_BACKUP_MAX_PLAINTEXT_BYTES) {
            "备份明文体积超过上限: $byteLength > $TIMETABLE_BACKUP_MAX_PLAINTEXT_BYTES"
        }
        return jsonString
    }

    private fun encodeProfile(p: ProfileBackupDto): JsonObject = buildJsonObject {
        put("profileId", p.profileId)

        putJsonArray("courses") {
            p.courses.forEach { c ->
                addJsonObject {
                    put("id", c.id)
                    put("courseName", c.courseName)
                    put("day", c.day)
                    put("startNode", c.startNode)
                    put("step", c.step)
                    put("startWeek", c.startWeek)
                    put("endWeek", c.endWeek)
                    put("type", c.type)
                }
            }
        }

        putJsonArray("semesters") {
            p.semesters.forEach { s ->
                addJsonObject {
                    put("id", s.id)
                    put("name", s.name)
                    if (s.startEpochDay != null) put("startEpochDay", s.startEpochDay) else put("startEpochDay", JsonNull)
                    put("totalWeeks", s.totalWeeks)
                    put("revision", s.revision)
                }
            }
        }

        putJsonArray("periods") {
            p.periods.forEach { pr ->
                addJsonObject {
                    put("semesterId", pr.semesterId)
                    put("number", pr.number)
                    put("startMinute", pr.startMinute)
                    put("endMinute", pr.endMinute)
                }
            }
        }

        putJsonArray("scheduledCourses") {
            p.scheduledCourses.forEach { sc ->
                addJsonObject {
                    put("id", sc.id)
                    put("semesterId", sc.semesterId)
                    put("courseName", sc.courseName)
                    put("teacher", sc.teacher)
                    put("room", sc.room)
                    put("color", sc.color)
                    if (sc.sourceCourseCode != null) put("sourceCourseCode", sc.sourceCourseCode) else put("sourceCourseCode", JsonNull)
                    if (sc.sourceClassCode != null) put("sourceClassCode", sc.sourceClassCode) else put("sourceClassCode", JsonNull)
                }
            }
        }

        putJsonArray("courseMeetings") {
            p.courseMeetings.forEach { cm ->
                addJsonObject {
                    put("id", cm.id)
                    put("courseId", cm.courseId)
                    put("day", cm.day)
                    if (cm.customStartMinute != null) put("customStartMinute", cm.customStartMinute) else put("customStartMinute", JsonNull)
                    if (cm.customEndMinute != null) put("customEndMinute", cm.customEndMinute) else put("customEndMinute", JsonNull)
                    if (cm.teacher != null) put("teacher", cm.teacher) else put("teacher", JsonNull)
                    if (cm.room != null) put("room", cm.room) else put("room", JsonNull)
                    put("kind", cm.kind)
                    if (cm.experimentProject != null) put("experimentProject", cm.experimentProject) else put("experimentProject", JsonNull)
                    if (cm.group != null) put("group", cm.group) else put("group", JsonNull)
                    put("isExperiment", cm.isExperiment)
                }
            }
        }

        putJsonArray("meetingWeeks") {
            p.meetingWeeks.forEach { mw ->
                addJsonObject {
                    put("meetingId", mw.meetingId)
                    put("week", mw.week)
                }
            }
        }

        putJsonArray("meetingSections") {
            p.meetingSections.forEach { ms ->
                addJsonObject {
                    put("meetingId", ms.meetingId)
                    put("section", ms.section)
                }
            }
        }

        putJsonArray("meetingDates") {
            p.meetingDates.forEach { md ->
                addJsonObject {
                    put("meetingId", md.meetingId)
                    put("epochDay", md.epochDay)
                }
            }
        }

        putJsonArray("meetingDeclaredWeeks") {
            p.meetingDeclaredWeeks.forEach { mdw ->
                addJsonObject {
                    put("meetingId", mdw.meetingId)
                    put("week", mdw.week)
                }
            }
        }

        putJsonArray("meetingExceptions") {
            p.meetingExceptions.forEach { me ->
                addJsonObject {
                    put("id", me.id)
                    put("meetingId", me.meetingId)
                    put("kind", me.kind)
                    if (me.originalEpochDay != null) put("originalEpochDay", me.originalEpochDay) else put("originalEpochDay", JsonNull)
                    if (me.targetEpochDay != null) put("targetEpochDay", me.targetEpochDay) else put("targetEpochDay", JsonNull)
                    if (me.customStartMinute != null) put("customStartMinute", me.customStartMinute) else put("customStartMinute", JsonNull)
                    if (me.customEndMinute != null) put("customEndMinute", me.customEndMinute) else put("customEndMinute", JsonNull)
                    put("hasSectionsOverride", me.hasSectionsOverride)
                    if (me.teacher != null) put("teacher", me.teacher) else put("teacher", JsonNull)
                    if (me.room != null) put("room", me.room) else put("room", JsonNull)
                    if (me.experimentProject != null) put("experimentProject", me.experimentProject) else put("experimentProject", JsonNull)
                    if (me.group != null) put("group", me.group) else put("group", JsonNull)
                }
            }
        }

        putJsonArray("exceptionSections") {
            p.exceptionSections.forEach { es ->
                addJsonObject {
                    put("exceptionId", es.exceptionId)
                    put("section", es.section)
                }
            }
        }

        putJsonArray("scheduleProvenance") {
            p.scheduleProvenance.forEach { sp ->
                addJsonObject {
                    put("id", sp.id)
                    if (sp.courseId != null) put("courseId", sp.courseId) else put("courseId", JsonNull)
                    if (sp.meetingId != null) put("meetingId", sp.meetingId) else put("meetingId", JsonNull)
                    if (sp.exceptionId != null) put("exceptionId", sp.exceptionId) else put("exceptionId", JsonNull)
                    put("position", sp.position)
                    if (sp.sourceId != null) put("sourceId", sp.sourceId) else put("sourceId", JsonNull)
                    if (sp.adapterId != null) put("adapterId", sp.adapterId) else put("adapterId", JsonNull)
                    if (sp.page != null) put("page", sp.page) else put("page", JsonNull)
                    if (sp.left != null) put("left", sp.left) else put("left", JsonNull)
                    if (sp.top != null) put("top", sp.top) else put("top", JsonNull)
                    if (sp.right != null) put("right", sp.right) else put("right", JsonNull)
                    if (sp.bottom != null) put("bottom", sp.bottom) else put("bottom", JsonNull)
                    put("rawText", sp.rawText)
                    if (sp.sourceLocator != null) put("sourceLocator", sp.sourceLocator) else put("sourceLocator", JsonNull)
                }
            }
        }

        putJsonArray("importNormalizationState") {
            p.importNormalizationState.forEach { ins ->
                addJsonObject {
                    put("courseId", ins.courseId)
                    put("version", ins.version)
                    put("payload", ins.payload)
                }
            }
        }

        putJsonArray("noticeAdjustmentEvents") {
            p.noticeAdjustmentEvents.forEach { nae ->
                addJsonObject {
                    put("id", nae.id)
                    put("requestKey", nae.requestKey)
                    put("semanticKey", nae.semanticKey)
                    put("confirmDigest", nae.confirmDigest)
                    put("scopeToken", nae.scopeToken)
                    put("semesterId", nae.semesterId)
                    put("kind", nae.kind)
                    if (nae.courseId != null) put("courseId", nae.courseId) else put("courseId", JsonNull)
                    if (nae.courseTitle != null) put("courseTitle", nae.courseTitle) else put("courseTitle", JsonNull)
                    if (nae.logicalBaseId != null) put("logicalBaseId", nae.logicalBaseId) else put("logicalBaseId", JsonNull)
                    if (nae.structureFingerprint != null) put("structureFingerprint", nae.structureFingerprint) else put("structureFingerprint", JsonNull)
                    if (nae.meetingId != null) put("meetingId", nae.meetingId) else put("meetingId", JsonNull)
                    if (nae.originalEpochDay != null) put("originalEpochDay", nae.originalEpochDay) else put("originalEpochDay", JsonNull)
                    if (nae.targetEpochDay != null) put("targetEpochDay", nae.targetEpochDay) else put("targetEpochDay", JsonNull)
                    if (nae.beforeJson != null) put("beforeJson", nae.beforeJson) else put("beforeJson", JsonNull)
                    if (nae.afterJson != null) put("afterJson", nae.afterJson) else put("afterJson", JsonNull)
                    put("afterFingerprint", nae.afterFingerprint)
                    put("payloadJson", nae.payloadJson)
                    if (nae.sourceText != null) put("sourceText", nae.sourceText) else put("sourceText", JsonNull)
                    put("sourceDigest", nae.sourceDigest)
                    if (nae.dateBasisJson != null) put("dateBasisJson", nae.dateBasisJson) else put("dateBasisJson", JsonNull)
                    put("appliedRevision", nae.appliedRevision)
                    if (nae.undoneRevision != null) put("undoneRevision", nae.undoneRevision) else put("undoneRevision", JsonNull)
                    put("state", nae.state)
                    if (nae.activeKey != null) put("activeKey", nae.activeKey) else put("activeKey", JsonNull)
                }
            }
        }

        putJsonArray("sequences") {
            p.sequences.forEach { seq ->
                addJsonObject {
                    put("name", seq.name)
                    put("seq", seq.seq)
                }
            }
        }

        putJsonArray("displayGroups") {
            p.displayGroups.forEach { dg ->
                addJsonObject {
                    put("storageKey", dg.storageKey)
                    put("rawJson", dg.rawJson)
                }
            }
        }
    }

    fun decode(jsonString: String): TimetableBackupPayloadDto {
        val byteLength = jsonString.toByteArray(Charsets.UTF_8).size
        require(byteLength <= TIMETABLE_BACKUP_MAX_PLAINTEXT_BYTES) {
            "备份明文体积超过上限: $byteLength > $TIMETABLE_BACKUP_MAX_PLAINTEXT_BYTES"
        }

        // 1. Strict lexical scan for duplicate keys and nest depth
        validateStrictJsonSyntax(jsonString)

        // 2. Strict parse to JsonObject
        val root = try {
            Json.parseToJsonElement(jsonString) as? JsonObject
                ?: throw IllegalArgumentException("JSON根节点必须为对象")
        } catch (e: Exception) {
            throw IllegalArgumentException("备份JSON解析失败: ${e.message}", e)
        }

        val allowedRootKeys = setOf(
            "formatVersion", "roomSchemaVersion", "schemaIdentityHash",
            "sourceAppId", "sourceVersionCode", "createdAt",
            "activeProfileId", "contentDigest", "profiles"
        )
        val unknownKeys = root.keys - allowedRootKeys
        require(unknownKeys.isEmpty()) { "检测到未知字段: ${unknownKeys.joinToString()}" }

        val formatVersion = root.getValue("formatVersion").asIntStrict()
        val roomSchemaVersion = root.getValue("roomSchemaVersion").asIntStrict()
        val schemaIdentityHash = root.getValue("schemaIdentityHash").asStringStrict()
        val sourceAppId = root.getValue("sourceAppId").asStringStrict()
        val sourceVersionCode = root.getValue("sourceVersionCode").asLongStrict()
        val createdAt = root.getValue("createdAt").asLongStrict()
        val activeProfileId = root.getValue("activeProfileId").asStringStrict()
        val contentDigest = root["contentDigest"]?.asStringStrict() ?: ""

        require(formatVersion == TIMETABLE_BACKUP_FORMAT_VERSION) { "不支持的备份格式版本: $formatVersion" }
        require(roomSchemaVersion == TIMETABLE_BACKUP_ROOM_SCHEMA_VERSION) { "不支持的RoomSchema版本: $roomSchemaVersion" }
        require(schemaIdentityHash == TIMETABLE_BACKUP_SCHEMA_IDENTITY_HASH) { "RoomSchema identityHash不匹配: $schemaIdentityHash" }

        val profilesArray = root.requireArray("profiles")
        require(profilesArray.size <= TIMETABLE_BACKUP_MAX_PROFILES) { "Profile数量超过限制: ${profilesArray.size}" }

        val profiles = profilesArray.map { decodeProfile(it.asJsonObjectStrict("Profile")) }
        val payload = TimetableBackupPayloadDto(
            formatVersion = formatVersion,
            roomSchemaVersion = roomSchemaVersion,
            schemaIdentityHash = schemaIdentityHash,
            sourceAppId = sourceAppId,
            sourceVersionCode = sourceVersionCode,
            createdAt = createdAt,
            activeProfileId = activeProfileId,
            profiles = profiles,
            contentDigest = contentDigest,
        )

        val totalRows = payload.totalRowCount()
        require(totalRows <= TIMETABLE_BACKUP_MAX_TOTAL_ROWS) { "总行数超过限制: $totalRows" }

        return payload
    }

    private fun decodeProfile(pObj: JsonObject): ProfileBackupDto {
        val allowedKeys = setOf(
            "profileId", "courses", "semesters", "periods", "scheduledCourses",
            "courseMeetings", "meetingWeeks", "meetingSections", "meetingDates",
            "meetingDeclaredWeeks", "meetingExceptions", "exceptionSections",
            "scheduleProvenance", "importNormalizationState", "noticeAdjustmentEvents",
            "sequences", "displayGroups"
        )
        val unknown = pObj.keys - allowedKeys
        require(unknown.isEmpty()) { "Profile包含未知字段: ${unknown.joinToString()}" }

        val profileId = pObj.getValue("profileId").asStringStrict()

        val courses = pObj.requireArray("courses").map {
            val obj = it.asJsonObjectStrict("courses row")
            val allowed = setOf("id", "courseName", "day", "startNode", "step", "startWeek", "endWeek", "type")
            require((obj.keys - allowed).isEmpty()) { "courses包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            CourseBackupDto(
                id = obj.getValue("id").asLongStrict(),
                courseName = obj.getValue("courseName").asStringStrict(),
                day = obj.getValue("day").asIntStrict(),
                startNode = obj.getValue("startNode").asIntStrict(),
                step = obj.getValue("step").asIntStrict(),
                startWeek = obj.getValue("startWeek").asIntStrict(),
                endWeek = obj.getValue("endWeek").asIntStrict(),
                type = obj.getValue("type").asIntStrict(),
            )
        }

        val semesters = pObj.requireArray("semesters").map {
            val obj = it.asJsonObjectStrict("semesters row")
            val allowed = setOf("id", "name", "startEpochDay", "totalWeeks", "revision")
            require((obj.keys - allowed).isEmpty()) { "semesters包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            SemesterBackupDto(
                id = obj.getValue("id").asLongStrict(),
                name = obj.getValue("name").asStringStrict(),
                startEpochDay = obj["startEpochDay"].asNullableLongStrict(),
                totalWeeks = obj.getValue("totalWeeks").asIntStrict(),
                revision = obj.getValue("revision").asLongStrict(),
            )
        }

        val periods = pObj.requireArray("periods").map {
            val obj = it.asJsonObjectStrict("periods row")
            val allowed = setOf("semesterId", "number", "startMinute", "endMinute")
            require((obj.keys - allowed).isEmpty()) { "periods包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            PeriodBackupDto(
                semesterId = obj.getValue("semesterId").asLongStrict(),
                number = obj.getValue("number").asIntStrict(),
                startMinute = obj.getValue("startMinute").asIntStrict(),
                endMinute = obj.getValue("endMinute").asIntStrict(),
            )
        }

        val scheduledCourses = pObj.requireArray("scheduledCourses").map {
            val obj = it.asJsonObjectStrict("scheduledCourses row")
            val allowed = setOf("id", "semesterId", "courseName", "teacher", "room", "color", "sourceCourseCode", "sourceClassCode")
            require((obj.keys - allowed).isEmpty()) { "scheduledCourses包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            ScheduledCourseBackupDto(
                id = obj.getValue("id").asLongStrict(),
                semesterId = obj.getValue("semesterId").asLongStrict(),
                courseName = obj.getValue("courseName").asStringStrict(),
                teacher = obj.getValue("teacher").asStringStrict(),
                room = obj.getValue("room").asStringStrict(),
                color = obj.getValue("color").asLongStrict(),
                sourceCourseCode = obj["sourceCourseCode"].asNullableStringStrict(),
                sourceClassCode = obj["sourceClassCode"].asNullableStringStrict(),
            )
        }

        val courseMeetings = pObj.requireArray("courseMeetings").map {
            val obj = it.asJsonObjectStrict("courseMeetings row")
            val allowed = setOf("id", "courseId", "day", "customStartMinute", "customEndMinute", "teacher", "room", "kind", "experimentProject", "group", "isExperiment")
            require((obj.keys - allowed).isEmpty()) { "courseMeetings包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            CourseMeetingBackupDto(
                id = obj.getValue("id").asLongStrict(),
                courseId = obj.getValue("courseId").asLongStrict(),
                day = obj.getValue("day").asIntStrict(),
                customStartMinute = obj["customStartMinute"].asNullableIntStrict(),
                customEndMinute = obj["customEndMinute"].asNullableIntStrict(),
                teacher = obj["teacher"].asNullableStringStrict(),
                room = obj["room"].asNullableStringStrict(),
                kind = obj.getValue("kind").asStringStrict(),
                experimentProject = obj["experimentProject"].asNullableStringStrict(),
                group = obj["group"].asNullableStringStrict(),
                isExperiment = obj.getValue("isExperiment").asBooleanStrict(),
            )
        }

        val meetingWeeks = pObj.requireArray("meetingWeeks").map {
            val obj = it.asJsonObjectStrict("meetingWeeks row")
            val allowed = setOf("meetingId", "week")
            require((obj.keys - allowed).isEmpty()) { "meetingWeeks包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            MeetingWeekBackupDto(
                meetingId = obj.getValue("meetingId").asLongStrict(),
                week = obj.getValue("week").asIntStrict(),
            )
        }

        val meetingSections = pObj.requireArray("meetingSections").map {
            val obj = it.asJsonObjectStrict("meetingSections row")
            val allowed = setOf("meetingId", "section")
            require((obj.keys - allowed).isEmpty()) { "meetingSections包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            MeetingSectionBackupDto(
                meetingId = obj.getValue("meetingId").asLongStrict(),
                section = obj.getValue("section").asIntStrict(),
            )
        }

        val meetingDates = pObj.requireArray("meetingDates").map {
            val obj = it.asJsonObjectStrict("meetingDates row")
            val allowed = setOf("meetingId", "epochDay")
            require((obj.keys - allowed).isEmpty()) { "meetingDates包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            MeetingDateBackupDto(
                meetingId = obj.getValue("meetingId").asLongStrict(),
                epochDay = obj.getValue("epochDay").asLongStrict(),
            )
        }

        val meetingDeclaredWeeks = pObj.requireArray("meetingDeclaredWeeks").map {
            val obj = it.asJsonObjectStrict("meetingDeclaredWeeks row")
            val allowed = setOf("meetingId", "week")
            require((obj.keys - allowed).isEmpty()) { "meetingDeclaredWeeks包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            MeetingDeclaredWeekBackupDto(
                meetingId = obj.getValue("meetingId").asLongStrict(),
                week = obj.getValue("week").asIntStrict(),
            )
        }

        val meetingExceptions = pObj.requireArray("meetingExceptions").map {
            val obj = it.asJsonObjectStrict("meetingExceptions row")
            val allowed = setOf("id", "meetingId", "kind", "originalEpochDay", "targetEpochDay", "customStartMinute", "customEndMinute", "hasSectionsOverride", "teacher", "room", "experimentProject", "group")
            require((obj.keys - allowed).isEmpty()) { "meetingExceptions包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            MeetingExceptionBackupDto(
                id = obj.getValue("id").asLongStrict(),
                meetingId = obj.getValue("meetingId").asLongStrict(),
                kind = obj.getValue("kind").asStringStrict(),
                originalEpochDay = obj["originalEpochDay"].asNullableLongStrict(),
                targetEpochDay = obj["targetEpochDay"].asNullableLongStrict(),
                customStartMinute = obj["customStartMinute"].asNullableIntStrict(),
                customEndMinute = obj["customEndMinute"].asNullableIntStrict(),
                hasSectionsOverride = obj.getValue("hasSectionsOverride").asBooleanStrict(),
                teacher = obj["teacher"].asNullableStringStrict(),
                room = obj["room"].asNullableStringStrict(),
                experimentProject = obj["experimentProject"].asNullableStringStrict(),
                group = obj["group"].asNullableStringStrict(),
            )
        }

        val exceptionSections = pObj.requireArray("exceptionSections").map {
            val obj = it.asJsonObjectStrict("exceptionSections row")
            val allowed = setOf("exceptionId", "section")
            require((obj.keys - allowed).isEmpty()) { "exceptionSections包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            ExceptionSectionBackupDto(
                exceptionId = obj.getValue("exceptionId").asLongStrict(),
                section = obj.getValue("section").asIntStrict(),
            )
        }

        val scheduleProvenance = pObj.requireArray("scheduleProvenance").map {
            val obj = it.asJsonObjectStrict("scheduleProvenance row")
            val allowed = setOf("id", "courseId", "meetingId", "exceptionId", "position", "sourceId", "adapterId", "page", "left", "top", "right", "bottom", "rawText", "sourceLocator")
            require((obj.keys - allowed).isEmpty()) { "scheduleProvenance包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            ScheduleProvenanceBackupDto(
                id = obj.getValue("id").asLongStrict(),
                courseId = obj["courseId"].asNullableLongStrict(),
                meetingId = obj["meetingId"].asNullableLongStrict(),
                exceptionId = obj["exceptionId"].asNullableLongStrict(),
                position = obj.getValue("position").asIntStrict(),
                sourceId = obj["sourceId"].asNullableStringStrict(),
                adapterId = obj["adapterId"].asNullableStringStrict(),
                page = obj["page"].asNullableIntStrict(),
                left = obj["left"].asNullableDoubleStrict(),
                top = obj["top"].asNullableDoubleStrict(),
                right = obj["right"].asNullableDoubleStrict(),
                bottom = obj["bottom"].asNullableDoubleStrict(),
                rawText = obj.getValue("rawText").asStringStrict(),
                sourceLocator = obj["sourceLocator"].asNullableStringStrict(),
            )
        }

        val importNormalizationState = pObj.requireArray("importNormalizationState").map {
            val obj = it.asJsonObjectStrict("importNormalizationState row")
            val allowed = setOf("courseId", "version", "payload")
            require((obj.keys - allowed).isEmpty()) { "importNormalizationState包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            ImportNormalizationStateBackupDto(
                courseId = obj.getValue("courseId").asLongStrict(),
                version = obj.getValue("version").asIntStrict(),
                payload = obj.getValue("payload").asStringStrict(),
            )
        }

        val noticeAdjustmentEvents = pObj.requireArray("noticeAdjustmentEvents").map {
            val obj = it.asJsonObjectStrict("noticeAdjustmentEvents row")
            val allowed = setOf(
                "id", "requestKey", "semanticKey", "confirmDigest", "scopeToken", "semesterId",
                "kind", "courseId", "courseTitle", "logicalBaseId", "structureFingerprint",
                "meetingId", "originalEpochDay", "targetEpochDay", "beforeJson", "afterJson",
                "afterFingerprint", "payloadJson", "sourceText", "sourceDigest", "dateBasisJson",
                "appliedRevision", "undoneRevision", "state", "activeKey"
            )
            require((obj.keys - allowed).isEmpty()) { "noticeAdjustmentEvents包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            NoticeAdjustmentEventBackupDto(
                id = obj.getValue("id").asLongStrict(),
                requestKey = obj.getValue("requestKey").asStringStrict(),
                semanticKey = obj.getValue("semanticKey").asStringStrict(),
                confirmDigest = obj.getValue("confirmDigest").asStringStrict(),
                scopeToken = obj.getValue("scopeToken").asStringStrict(),
                semesterId = obj.getValue("semesterId").asLongStrict(),
                kind = obj.getValue("kind").asStringStrict(),
                courseId = obj["courseId"].asNullableLongStrict(),
                courseTitle = obj["courseTitle"].asNullableStringStrict(),
                logicalBaseId = obj["logicalBaseId"].asNullableStringStrict(),
                structureFingerprint = obj["structureFingerprint"].asNullableStringStrict(),
                meetingId = obj["meetingId"].asNullableLongStrict(),
                originalEpochDay = obj["originalEpochDay"].asNullableLongStrict(),
                targetEpochDay = obj["targetEpochDay"].asNullableLongStrict(),
                beforeJson = obj["beforeJson"].asNullableStringStrict(),
                afterJson = obj["afterJson"].asNullableStringStrict(),
                afterFingerprint = obj.getValue("afterFingerprint").asStringStrict(),
                payloadJson = obj.getValue("payloadJson").asStringStrict(),
                sourceText = obj["sourceText"].asNullableStringStrict(),
                sourceDigest = obj.getValue("sourceDigest").asStringStrict(),
                dateBasisJson = obj["dateBasisJson"].asNullableStringStrict(),
                appliedRevision = obj.getValue("appliedRevision").asLongStrict(),
                undoneRevision = obj["undoneRevision"].asNullableLongStrict(),
                state = obj.getValue("state").asStringStrict(),
                activeKey = obj["activeKey"].asNullableStringStrict(),
            )
        }

        val sequences = pObj.requireArray("sequences").map {
            val obj = it.asJsonObjectStrict("sequences row")
            val allowed = setOf("name", "seq")
            require((obj.keys - allowed).isEmpty()) { "sequences包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            SqliteSequenceBackupDto(
                name = obj.getValue("name").asStringStrict(),
                seq = obj.getValue("seq").asLongStrict(),
            )
        }

        val displayGroups = pObj.requireArray("displayGroups").map {
            val obj = it.asJsonObjectStrict("displayGroups row")
            val allowed = setOf("storageKey", "rawJson")
            require((obj.keys - allowed).isEmpty()) { "displayGroups包含未知字段: ${(obj.keys - allowed).joinToString()}" }
            CourseDisplayGroupBackupDto(
                storageKey = obj.getValue("storageKey").asStringStrict(),
                rawJson = obj.getValue("rawJson").asStringStrict(),
            )
        }

        return ProfileBackupDto(
            profileId = profileId,
            courses = courses,
            semesters = semesters,
            periods = periods,
            scheduledCourses = scheduledCourses,
            courseMeetings = courseMeetings,
            meetingWeeks = meetingWeeks,
            meetingSections = meetingSections,
            meetingDates = meetingDates,
            meetingDeclaredWeeks = meetingDeclaredWeeks,
            meetingExceptions = meetingExceptions,
            exceptionSections = exceptionSections,
            scheduleProvenance = scheduleProvenance,
            importNormalizationState = importNormalizationState,
            noticeAdjustmentEvents = noticeAdjustmentEvents,
            sequences = sequences,
            displayGroups = displayGroups,
        )
    }

    private fun JsonElement.asJsonObjectStrict(tag: String): JsonObject {
        return this as? JsonObject ?: throw IllegalArgumentException("$tag 必须为对象类型")
    }

    private fun JsonElement.asStringStrict(): String {
        val p = jsonPrimitive
        require(p.isString) { "字段必须为字符串类型" }
        return p.content
    }

    private fun JsonElement.asIntStrict(): Int {
        val p = jsonPrimitive
        require(!p.isString) { "整数字段不能为字符串" }
        return p.int
    }

    private fun JsonElement.asLongStrict(): Long {
        val p = jsonPrimitive
        require(!p.isString) { "数值字段不能为字符串" }
        return p.long
    }

    private fun JsonElement.asBooleanStrict(): Boolean {
        val p = jsonPrimitive
        require(!p.isString) { "布尔字段不能为字符串" }
        return p.boolean
    }

    private fun JsonElement?.asNullableStringStrict(): String? {
        if (this == null || this is JsonNull) return null
        val p = jsonPrimitive
        require(p.isString) { "字段必须为字符串类型" }
        return p.content
    }

    private fun JsonElement?.asNullableIntStrict(): Int? {
        if (this == null || this is JsonNull) return null
        val p = jsonPrimitive
        require(!p.isString) { "整数字段不能为字符串" }
        return p.int
    }

    private fun JsonElement?.asNullableLongStrict(): Long? {
        if (this == null || this is JsonNull) return null
        val p = jsonPrimitive
        require(!p.isString) { "数值字段不能为字符串" }
        return p.long
    }

    private fun JsonElement?.asNullableDoubleStrict(): Double? {
        if (this == null || this is JsonNull) return null
        val p = jsonPrimitive
        require(!p.isString) { "浮点数字段不能为字符串" }
        return p.double
    }

    private fun JsonObject.requireArray(key: String): JsonArray {
        val element = this[key] ?: throw IllegalArgumentException("缺少必需的数组字段: $key")
        return element as? JsonArray ?: throw IllegalArgumentException("字段 $key 必须为数组")
    }

    /**
     * Scans JSON string to reject duplicate object keys and ensure depth <= 64.
     */
    internal fun validateStrictJsonSyntax(json: String) {
        var index = 0
        val length = json.length
        var depth = 0
        val objectKeysStack = ArrayDeque<MutableSet<String>>()

        fun skipWhitespace() {
            while (index < length && json[index].isWhitespace()) {
                index++
            }
        }

        fun parseString(): String {
            require(index < length && json[index] == '"') { "无效的字符串起始" }
            index++
            val sb = java.lang.StringBuilder()
            while (index < length) {
                val c = json[index++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    require(index < length) { "转义字符未闭合" }
                    val esc = json[index++]
                    when (esc) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            require(index + 4 <= length) { "Unicode转义不全" }
                            val hex = json.substring(index, index + 4)
                            sb.append(hex.toInt(16).toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("非法转义字符: \\$esc")
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("字符串未闭合")
        }

        fun skipNumberOrLiteral() {
            while (index < length) {
                val c = json[index]
                if (c == ',' || c == '}' || c == ']' || c.isWhitespace()) break
                index++
            }
        }

        fun parseValue() {
            skipWhitespace()
            require(index < length) { "JSON意外结束" }
            val c = json[index]
            when (c) {
                '{' -> {
                    depth++
                    require(depth <= TIMETABLE_BACKUP_MAX_JSON_DEPTH) { "JSON嵌套深度超过最大限制 $TIMETABLE_BACKUP_MAX_JSON_DEPTH" }
                    index++
                    val keys = mutableSetOf<String>()
                    objectKeysStack.addLast(keys)
                    skipWhitespace()
                    if (index < length && json[index] == '}') {
                        index++
                        objectKeysStack.removeLast()
                        depth--
                        return
                    }
                    while (index < length) {
                        skipWhitespace()
                        val key = parseString()
                        if (!keys.add(key)) {
                            throw IllegalArgumentException("检测到重复的JSON键: $key")
                        }
                        skipWhitespace()
                        require(index < length && json[index] == ':') { "键值对缺少冒号" }
                        index++
                        parseValue()
                        skipWhitespace()
                        require(index < length) { "对象未闭合" }
                        if (json[index] == ',') {
                            index++
                            skipWhitespace()
                            continue
                        } else if (json[index] == '}') {
                            index++
                            break
                        } else {
                            throw IllegalArgumentException("非法的对象分隔符: ${json[index]}")
                        }
                    }
                    objectKeysStack.removeLast()
                    depth--
                }
                '[' -> {
                    depth++
                    require(depth <= TIMETABLE_BACKUP_MAX_JSON_DEPTH) { "JSON嵌套深度超过最大限制 $TIMETABLE_BACKUP_MAX_JSON_DEPTH" }
                    index++
                    skipWhitespace()
                    if (index < length && json[index] == ']') {
                        index++
                        depth--
                        return
                    }
                    while (index < length) {
                        parseValue()
                        skipWhitespace()
                        require(index < length) { "数组未闭合" }
                        if (json[index] == ',') {
                            index++
                            skipWhitespace()
                            continue
                        } else if (json[index] == ']') {
                            index++
                            break
                        } else {
                            throw IllegalArgumentException("非法的数组分隔符: ${json[index]}")
                        }
                    }
                    depth--
                }
                '"' -> {
                    parseString()
                }
                else -> {
                    skipNumberOrLiteral()
                }
            }
        }

        parseValue()
        skipWhitespace()
        require(index == length) { "JSON末尾存在多余内容" }
    }
}
