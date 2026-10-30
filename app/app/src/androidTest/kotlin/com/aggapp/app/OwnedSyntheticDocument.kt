package com.aggapp.app

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.util.UUID
import org.junit.Assert.assertEquals

/** Standard MediaStore ownership: only creates/deletes this test's public fixture.
 * The import itself still uses DocumentsUI; no replacement URI is passed to the app.
 * https://developer.android.com/training/data-storage/shared/media#add-item
 */
internal class OwnedSyntheticDocument private constructor(private val uri:Uri,val displayName:String):Closeable {
    override fun close() {
        val resolver=InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        assertEquals("Only the test-owned document must be removed",1,resolver.delete(uri,null,null))
    }
    companion object {
        fun create(assetName:String,mime:String):OwnedSyntheticDocument {
            check(Build.VERSION.SDK_INT>=29) {"Self-contained Download fixtures require Android 10; host remains minSdk26"}
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val resolver=instrumentation.targetContext.contentResolver
            val extension=assetName.substringAfterLast('.')
            val name="school-helper-synthetic-${UUID.randomUUID()}.$extension"
            val uri=checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,name)
                put(MediaStore.MediaColumns.MIME_TYPE,mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING,1)
            }))
            try {
                instrumentation.context.assets.open("fixtures/$assetName").use {input ->
                    checkNotNull(resolver.openOutputStream(uri,"w")).use {output ->input.copyTo(output)}
                }
                check(resolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)==1)
                val actualName=checkNotNull(resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)).use {cursor ->
                    check(cursor.moveToFirst());cursor.getString(0)
                }
                return OwnedSyntheticDocument(uri,actualName)
            } catch(failure:Throwable) {resolver.delete(uri,null,null);throw failure}
        }
    }
}
