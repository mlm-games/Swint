package org.mlm.mages.platform

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun CallWebViewHost(
    widgetUrl: String,
    onMessageFromWidget: (String) -> Unit,
    onClosed: () -> Unit
): CallWebViewController {
    val context = androidx.compose.ui.platform.LocalContext.current
    val webView = remember { WebView(context) }

    DisposableEffect(Unit) {
        webView.settings.javaScriptEnabled = true

        // JS -> Kotlin bridge
        class Bridge {
            @JavascriptInterface
            fun postMessage(json: String) {
                onMessageFromWidget(json)
            }
        }
        webView.addJavascriptInterface(Bridge(), "MagesBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Inject listener to forward window.postMessage / message events to MagesBridge
                view.evaluateJavascript(
                    """
                    (function() {
                        if (window.__MagesBridgeInstalled) return;
                        window.__MagesBridgeInstalled = true;
                        window.addEventListener('message', function(ev) {
                            try {
                                var data = ev.data;
                                if (typeof data === 'string') {
                                    MagesBridge.postMessage(data);
                                } else {
                                    MagesBridge.postMessage(JSON.stringify(data));
                                }
                            } catch (e) {}
                        });
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }

        webView.loadUrl(widgetUrl)

        onDispose {
            try {
                webView.destroy()
            } catch (_: Throwable) {
            }
            onClosed()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { webView }
    )

    return remember {
        object : CallWebViewController {
            override fun sendToWidget(message: String) {
                // `message` is a JSON string (object/array), need to embed safely
                val escaped = JSONObject.quote(message) // produces a quoted JSON string literal
                val script = "window.postMessage(JSON.parse($escaped), '*');"
                webView.post {
                    try {
                        webView.evaluateJavascript(script, null)
                    } catch (_: Throwable) {
                    }
                }
            }

            override fun close() {
                try {
                    webView.destroy()
                } catch (_: Throwable) {
                }
                onClosed()
            }
        }
    }
}