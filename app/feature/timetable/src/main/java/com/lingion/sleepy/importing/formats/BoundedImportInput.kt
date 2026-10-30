/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Reads but never closes the caller-owned stream. Cancellation exceptions propagate unchanged. */
object BoundedImportInput {
    fun readUtf8(input: InputStream, context: FormatParseContext): String {
        val output = ByteArrayOutputStream(minOf(8192, context.limits.maxInputBytes))
        val buffer = ByteArray(8192)
        try {
            while (true) {
                context.cancellation.check()
                val count = input.read(buffer, 0, minOf(buffer.size, context.limits.maxInputBytes - output.size() + 1))
                if (count < 0) break
                if (count == 0) {
                    val one = input.read()
                    if (one < 0) break
                    if (output.size() == context.limits.maxInputBytes) reject("LIMIT_EXCEEDED")
                    output.write(one)
                } else {
                    if (count > context.limits.maxInputBytes - output.size()) reject("LIMIT_EXCEEDED")
                    output.write(buffer, 0, count)
                }
            }
        } catch (_: IOException) { reject("IMPORT_UNREADABLE") }
        context.cancellation.check()
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
                .removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) { reject("INVALID_UTF8") }
    }
}
