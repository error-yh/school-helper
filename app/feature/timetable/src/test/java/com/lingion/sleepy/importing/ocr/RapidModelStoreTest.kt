package com.lingion.sleepy.importing.ocr

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Reuse the existing atomic model store for pinned ONNX assets, never trust stale caches. */
class RapidModelStoreTest {
    private fun spec(bytes:ByteArray)=OcrModelSpec("ch_PP-OCRv4_det_infer.onnx",bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
    private fun withRoot(block:(File)->Unit) {
        val root=Files.createTempDirectory("rapid-model-test").toFile()
        try {block(root)} finally {root.deleteRecursively()}
    }
    @Test fun pinnedOnnxIsInstalledAndVerifiedCacheIsReused()=withRoot {root->
        val bytes=byteArrayOf(1,2,3,4); var opens=0
        val store=OcrModelStore(root,listOf(spec(bytes))) {opens++;ByteArrayInputStream(bytes)}
        assertEquals(root,store.prepare());assertEquals(root,store.prepare())
        assertEquals(1,opens);assertArrayEquals(bytes,File(root,"tessdata/${spec(bytes).filename}").readBytes())
    }
    @Test fun sameSizeCorruptOnnxIsRepairedFromTrustedAsset()=withRoot {root->
        val bytes=byteArrayOf(1,2,3);val model=spec(bytes)
        File(root,"tessdata").mkdirs();File(root,"tessdata/${model.filename}").writeBytes(byteArrayOf(3,2,1))
        OcrModelStore(root,listOf(model)){ByteArrayInputStream(bytes)}.prepare()
        assertArrayEquals(bytes,File(root,"tessdata/${model.filename}").readBytes())
    }
    @Test fun badAssetCannotReplaceExistingModelAndTemporaryFileIsRemoved()=withRoot {root->
        val model=spec(byteArrayOf(1,2,3));File(root,"tessdata").mkdirs()
        val file=File(root,"tessdata/${model.filename}");val old=byteArrayOf(8,8,8);file.writeBytes(old)
        assertThrows(OcrModelException::class.java){OcrModelStore(root,listOf(model)){ByteArrayInputStream(byteArrayOf(9,9,9))}.prepare()}
        assertArrayEquals(old,file.readBytes());assertEquals(listOf(model.filename),file.parentFile!!.list()!!.toList())
    }
    @Test fun cancelledInstallLeavesNoTemporaryModel()=withRoot {root->
        val bytes=ByteArray(70_000){1};var checks=0
        assertThrows(java.util.concurrent.CancellationException::class.java){
            OcrModelStore(root,listOf(spec(bytes))){ByteArrayInputStream(bytes)}.prepare {
                if(++checks==5) throw java.util.concurrent.CancellationException()
            }
        }
        assertTrue(File(root,"tessdata").listFiles().orEmpty().isEmpty())
    }
    @Test fun arbitraryPathsAndNonModelExtensionsRemainRejected() {
        listOf("../a.onnx","/a.onnx","a/b.onnx","a\\b.onnx","a.jar","a.onnx.tmp","a..b.onnx").forEach {name->
            assertThrows(IllegalArgumentException::class.java){OcrModelSpec(name,1,"0".repeat(64))}
        }
    }
    @Test fun actualPinnedClassifierVersionIsAValidModelBasename() {
        val name="ch_ppocr_mobile_v2.0_cls_infer.onnx"
        assertEquals(name,OcrModelSpec(name,585532,"e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c").filename)
    }
}
