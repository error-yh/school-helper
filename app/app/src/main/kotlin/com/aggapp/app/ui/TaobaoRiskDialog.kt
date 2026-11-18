package com.aggapp.app.ui

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** One WebView, one poll loop. Disposing the dialog also destroys its handler and WebView. */
@Composable
fun TaobaoRiskDialog(riskUrl: String, cookies: List<Pair<String, String>>, onPassed: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("淘宝 · 安全验证") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        text = {
            if (!TaobaoRiskRules.isTrustedPage(riskUrl)) Text("验证地址异常，请取消并重新登录。")
            else AndroidView(modifier = Modifier.fillMaxWidth().height(440.dp),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        val manager = CookieManager.getInstance()
                        manager.setAcceptCookie(true)
                        cookies.forEach { (url, cookie) -> manager.setCookie(url, cookie) }
                        manager.flush()
                        val initial = manager.getCookie("https://www.taobao.com/").orEmpty()
                        val handler = Handler(Looper.getMainLooper())
                        var finished = false
                        var navigated = false
                        val poll = object : Runnable {
                            override fun run() {
                                if (finished) return
                                val current = manager.getCookie("https://www.taobao.com/").orEmpty()
                                if (TaobaoRiskRules.canFinish(initial, current, navigated)) {
                                    finished = true
                                    handler.removeCallbacksAndMessages(null)
                                    onPassed(current)
                                } else handler.postDelayed(this, 1500)
                            }
                        }
                        tag = { finished = true; handler.removeCallbacksAndMessages(null) }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                !TaobaoRiskRules.isTrustedPage(request.url.toString())
                            override fun onPageFinished(view: WebView, url: String) {
                                navigated = url != riskUrl && TaobaoRiskRules.isCompletionPage(url)
                                handler.removeCallbacks(poll)
                                if (!finished) handler.postDelayed(poll, 1000)
                            }
                        }
                        loadUrl(riskUrl)
                        handler.postDelayed(poll, 1500)
                    }
                }, onRelease = { view ->
                    @Suppress("UNCHECKED_CAST")
                    (view.tag as? (() -> Unit))?.invoke()
                    view.stopLoading()
                    view.webViewClient = WebViewClient()
                    view.destroy()
                })
        })
}
