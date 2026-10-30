/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.ui

import android.content.Context
import com.lingion.sleepy.importing.ocr.rapidOcrConfig

/** Device lifecycle checks exercise the production configuration and verified model store. */
internal fun rapidTrialConfig(context:Context,width:Int,height:Int)=rapidOcrConfig(context,width,height)