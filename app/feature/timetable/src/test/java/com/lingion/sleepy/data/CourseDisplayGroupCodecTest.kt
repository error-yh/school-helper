package com.lingion.sleepy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CourseDisplayGroupCodecTest {
    private fun member(courseId: Long, signature: String = "课程|code|class") = CourseGroupMember(courseId, signature)

    private fun group(id: String = "group-1", name: String = "Hadoop 实验", members: List<CourseGroupMember> = listOf(member(1), member(2))) =
        CourseDisplayGroup(id, name, members)

    @Test
    fun roundTripKeepsGroupsNamesAndMembers() {
        val groups = listOf(
            group("g-1", "Hadoop 实验", listOf(member(1, "a|b|c"), member(2, "a|b|c"), member(3, "d|e|f"))),
            group("g-2", "体育", listOf(member(4, "体育||"), member(5, "体育||"))),
        )
        val decoded = CourseDisplayGroupCodec.decode(CourseDisplayGroupCodec.encode(groups))

        assertEquals(groups, decoded)
    }

    @Test
    fun encodedValueIsStableForTheSameInput() {
        val groups = listOf(group())
        assertEquals(CourseDisplayGroupCodec.encode(groups), CourseDisplayGroupCodec.encode(groups))
        assertTrue(CourseDisplayGroupCodec.encode(groups).contains("\"version\":1"))
    }

    @Test
    fun brokenJsonIsRejected() {
        listOf(
            "not json",
            "[]",
            "{\"version\":1}",
            "{\"version\":1,\"groups\":[]}extra",
            "{\"version\":2,\"groups\":[]}",
            "{\"version\":\"1\",\"groups\":[]}",
            "{\"version\":1,\"groups\":[],\"extra\":1}",
            "{\"version\":1,\"groups\":[{\"id\":\"g\",\"name\":\"n\",\"members\":[{\"courseId\":1,\"signature\":\"s\"}]}]}",
            "{\"version\":1,\"groups\":[{\"id\":\"g\",\"name\":\"n\",\"members\":[{\"courseId\":1,\"signature\":\"s\"},{\"courseId\":1,\"signature\":\"s\"}]}]}",
            "{\"version\":1,\"groups\":[{\"id\":\"g\",\"name\":\"\",\"members\":[{\"courseId\":1,\"signature\":\"s\"},{\"courseId\":2,\"signature\":\"s\"}]}]}",
            "{\"version\":1,\"groups\":[{\"id\":\"g\",\"name\":\"n\",\"members\":[{\"courseId\":\"1\",\"signature\":\"s\"},{\"courseId\":2,\"signature\":\"s\"}]}]}",
            "{\"version\":1,\"groups\":[{\"id\":\"g\",\"name\":\"n\",\"members\":[{\"courseId\":1,\"signature\":\"\"},{\"courseId\":2,\"signature\":\"s\"}]}]}",
            "{\"version\":1,\"groups\":[{\"id\":\"g\",\"name\":\"n\",\"members\":[{\"courseId\":1,\"signature\":\"s\",\"other\":1},{\"courseId\":2,\"signature\":\"s\"}]}]}",
        ).forEach { broken ->
            try {
                CourseDisplayGroupCodec.decode(broken)
                fail("corrupt value must not decode: $broken")
            } catch (_: IllegalArgumentException) {
                // expected: a corrupt configuration never becomes an empty/partial group list
            }
        }
    }

    @Test
    fun tooManyGroupsAreRejectedOnEncode() {
        val one = group()
        try {
            CourseDisplayGroupCodec.encode(List(201) { one.copy(id = "g-$it") })
            fail("unbounded group count must not encode")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun storageKeyIsolatesScopeAndSemester() {
        val a = CourseDisplayGroupKey("local", 1)
        val b = CourseDisplayGroupKey("local", 2)
        val c = CourseDisplayGroupKey("other-profile", 1)
        assertEquals(3, setOf(a.storageKey, b.storageKey, c.storageKey).size)
        assertTrue(a.storageKey.contains("local"))
        assertTrue(a.storageKey.contains("semester=1"))
    }
}