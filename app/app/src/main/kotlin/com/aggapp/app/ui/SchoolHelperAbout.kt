package com.aggapp.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aggapp.app.update.AppUpdate
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SchoolHelperAbout() {
    var document by remember { mutableStateOf<LicenseDocument?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("校园助手", style = MaterialTheme.typography.titleMedium)
            Text(AppUpdate.statusMessage, style = MaterialTheme.typography.bodySmall)
            Text("基于码上取与 Sleepy，保留原作者署名。", style = MaterialTheme.typography.bodySmall)
            LicenseDocument.entries.forEach { entry ->
                TextButton(onClick = { document = entry }) { Text(entry.title) }
            }
        }
    }
    document?.let { entry ->
        val context = LocalContext.current
        var content by remember(entry) { mutableStateOf("正在读取…") }
        LaunchedEffect(entry) {
            content = try {
                withContext(Dispatchers.IO) {
                    entry.assetPaths.joinToString("\n\n") { path ->
                        "=== $path ===\n" + context.assets.open(path).bufferedReader().use { it.readText() }
                    }
                }
            } catch (_: IOException) {
                "无法读取许可文本，请查看随源码提供的 LICENSES 目录。"
            }
        }
        AlertDialog(
            onDismissRequest = { document = null },
            title = { Text(entry.title) },
            text = {
                Text(content, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = { document = null }) { Text("关闭") } },
        )
    }
}

private enum class LicenseDocument(val title: String, vararg val assetPaths: String) {
    NOTICE("开源来源与修改说明", "open_source/NOTICE.txt"),
    MIT("码上取 · MIT 许可", "open_source/MIT-mashangqu.txt"),
    GPL("Sleepy 与组合程序 · GPL-3.0", "open_source/GPL-3.0-sleepy.txt"),
    PDF("PDF 提取组件许可与声明", "open_source/Apache-2.0-pdfbox-android.txt", "open_source/NOTICE-pdfbox-android.txt"),
    OCR("离线文字识别组件与模型许可", "ocr/licenses/README.md", "ocr/licenses/wrapper-LICENSE",
        "ocr/licenses/tesseract-LICENSE", "ocr/licenses/tessdata-LICENSE", "ocr/licenses/leptonica-license",
        "ocr/licenses/libjpeg-README", "ocr/licenses/libpng-LICENSE",
        "ocr/rapid/LICENSE-RapidOCR4j.txt", "ocr/rapid/LICENSE-PaddleOCR.txt",
        "ocr/rapid/LICENSE-Clipper.txt", "ocr/rapid/LICENSE-OpenCV.txt", "ocr/rapid/LICENSE-ONNXRuntime.txt"),
}
