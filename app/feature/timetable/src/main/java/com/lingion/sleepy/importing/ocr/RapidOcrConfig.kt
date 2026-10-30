/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import android.content.Context
import io.github.hzkitty.entity.OcrConfig
import java.io.File

/** Uses upstream resizing options; retains small printed text without unbounded short-edge scaling. */
internal fun rapidOcrConfig(context:Context,width:Int,height:Int,checkCancellation:()->Unit={}):OcrConfig {
    require(width>0 && height>0)
    val root=File(context.noBackupFilesDir,"rapid-models-v1")
    OcrModelStore(root,RAPID_MODELS){context.assets.open("ocr/rapid/$it")}.prepare(checkCancellation)
    val long=maxOf(width,height)
    val short=minOf(width,height)
    val boundedShort=(1920L*short/long).toInt()
    return OcrConfig().apply {
        global.printVerbose=false;global.useCls=false
        global.maxSideLen=1920;global.minSideLen=1
        det.modelPath=File(root,"tessdata/ch_PP-OCRv4_det_infer.onnx").absolutePath
        cls.modelPath=File(root,"tessdata/ch_ppocr_mobile_v2.0_cls_infer.onnx").absolutePath
        rec.modelPath=File(root,"tessdata/ch_PP-OCRv4_rec_infer.onnx").absolutePath
        // 1920 is a multiple of upstream's 32-pixel alignment. Extremely thin
        // inputs use its max mode rather than amplifying a minimum of 32 pixels.
        det.limitType=if(boundedShort>=32) "min" else "max"
        det.limitSideLen=if(boundedShort>=32) minOf(1600,boundedShort) else 1920
        det.intraOpNumThreads=2;det.interOpNumThreads=1;det.useArena=false
        cls.intraOpNumThreads=2;cls.interOpNumThreads=1;cls.useArena=false
        rec.intraOpNumThreads=2;rec.interOpNumThreads=1;rec.useArena=false
    }
}

private val RAPID_MODELS=listOf(
    OcrModelSpec("ch_PP-OCRv4_det_infer.onnx",4745517,"d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9"),
    OcrModelSpec("ch_PP-OCRv4_rec_infer.onnx",10857958,"48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b"),
    OcrModelSpec("ch_ppocr_mobile_v2.0_cls_infer.onnx",585532,"e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c"),
)