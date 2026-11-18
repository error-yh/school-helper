package com.aggapp.app.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Adapted from MaShangQu-APP f24fa4b: bulk pixels, bounded memory and off-main rendering. */
object BarcodeRenderer {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    fun cached(content: String, width: Int, height: Int): Bitmap? = cache.get("$content|$width|$height")
    fun render(content: String, width: Int = 1000, height: Int = 300): Bitmap? {
        cached(content, width, height)?.let { return it }
        val pixels = BarcodeRaster.render(content, width, height) ?: return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        cache.put("$content|$width|$height", bitmap)
        return bitmap
    }
}

@Composable
fun BarcodeImage(content: String, modifier: Modifier = Modifier, width: Int = 1000, height: Int = 300,
    contentDescription: String? = null, contentScale: ContentScale = ContentScale.Fit) {
    var bitmap by remember(content, width, height) { mutableStateOf(BarcodeRenderer.cached(content, width, height)) }
    var failed by remember(content, width, height) { mutableStateOf(false) }
    LaunchedEffect(content, width, height) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.Default) { BarcodeRenderer.render(content, width, height) }
            failed = bitmap == null
        }
    }
    val image = bitmap?.asImageBitmap()
    if (image != null) Image(image, contentDescription, modifier, contentScale = contentScale)
    else Box(modifier, contentAlignment = Alignment.Center) {
        if (failed) Text("条码渲染失败", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
