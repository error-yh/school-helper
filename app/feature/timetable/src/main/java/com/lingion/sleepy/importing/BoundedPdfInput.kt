/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing
import java.io.InputStream
class PdfInputLimitException: java.io.IOException("PDF input exceeds the byte limit")
internal object BoundedPdfInput {
    fun read(input: InputStream, maxBytes: Int, checkCancellation: () -> Unit = {}): ByteArray {
        require(maxBytes in 1..50_000_000)
        val result=java.io.ByteArrayOutputStream(minOf(maxBytes,8192))
        val buffer=ByteArray(8192)
        while(true) {
            checkCancellation()
            val count=input.read(buffer,0,minOf(buffer.size,maxBytes-result.size()+1))
            if(count<0) break
            if(count==0) { val next=input.read(); if(next<0) break; if(result.size()==maxBytes) throw PdfInputLimitException(); result.write(next); continue }
            if(count>maxBytes-result.size()) throw PdfInputLimitException()
            result.write(buffer,0,count)
        }
        return result.toByteArray()
    }
}
