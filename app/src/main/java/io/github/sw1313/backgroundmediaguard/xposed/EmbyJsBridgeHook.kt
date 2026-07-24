package io.github.sw1313.backgroundmediaguard.xposed

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * Emby-specific bridge: episode auto-next is decided in WebView JS.
 *
 * jadx path:
 * LocalPlaybackExoPlayer.onPlaybackEnd
 *   -> messenger.sendMessage("…onEvent('ended')")
 *   -> LocalBroadcast ACTION_SEND_MESSAGE
 *   -> MainActivity.jsMessageReceiver
 *   -> RespondToWebView -> NativeWebView.sendJavaScript -> evaluateJavascript
 *
 * Native ExoPlayer queue is usually size=1; seekToNextMediaItem alone cannot
 * start the next episode. JS must stay runnable after MainActivity stops.
 */
class EmbyJsBridgeHook(
    private val module: ModuleEntry,
    private val packageName: String,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webViewRef: WeakReference<WebView>? = null

    fun install(classLoader: ClassLoader) {
        var hooks = 0

        hooks += hookNativeSendJavaScript(classLoader)
        hooks += hookLocalBroadcastBackup(classLoader)
        hooks += hookCaptureWebView(classLoader)

        if (hooks == 0) {
            error("Emby JS 桥接 Hook 全部失败")
        }
        module.info("已为 $packageName 安装 Emby JS 桥接：hooks=$hooks")
    }

    private fun hookCaptureWebView(classLoader: ClassLoader): Int {
        val clazz = runCatching {
            Class.forName("com.mb.android.webviews.MySystemWebView", false, classLoader)
        }.getOrNull() ?: return 0

        var count = 0
        clazz.declaredConstructors.forEach { ctor ->
            val ok = runCatching {
                ctor.isAccessible = true
                module.hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    val webView = chain.thisObject as? WebView
                    if (webView != null) {
                        webViewRef = WeakReference(webView)
                        module.info("Emby WebView 已缓存")
                    }
                    result
                }
                true
            }.onFailure {
                module.warn("跳过 MySystemWebView.<init>: ${it.message}")
            }.getOrDefault(false)
            if (ok) count++
        }
        return count
    }

    private fun hookNativeSendJavaScript(classLoader: ClassLoader): Int {
        val clazz = runCatching {
            Class.forName("com.mb.android.webviews.NativeWebView", false, classLoader)
        }.getOrNull() ?: return 0
        val method = runCatching {
            clazz.getDeclaredMethod("sendJavaScript", String::class.java)
        }.getOrNull() ?: return 0
        val field = runCatching {
            clazz.getDeclaredField("webView").apply { isAccessible = true }
        }.getOrNull()

        return runCatching {
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val js = chain.args[0] as? String
                val webView = field?.get(chain.thisObject) as? WebView
                if (webView != null) {
                    webViewRef = WeakReference(webView)
                }
                wakeWebView(webView)
                if (js != null && (js.contains("onEvent('ended')") || js.contains("onEvent(\"ended\")"))) {
                    module.info("Emby sendJavaScript(ended): $js")
                }
                chain.proceed()
            }
            1
        }.onFailure {
            module.warn("NativeWebView.sendJavaScript Hook 失败: ${it.message}")
        }.getOrDefault(0)
    }

    /**
     * If MainActivity receiver is gone / late, still deliver ended (and other)
     * messages straight into the cached WebView.
     */
    private fun hookLocalBroadcastBackup(classLoader: ClassLoader): Int {
        val clazz = runCatching {
            Class.forName(
                "androidx.localbroadcastmanager.content.LocalBroadcastManager",
                false,
                classLoader,
            )
        }.getOrNull() ?: return 0
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "sendBroadcast" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == Intent::class.java
        } ?: return 0

        return runCatching {
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val intent = chain.args[0] as? Intent
                if (intent?.action == ACTION_SEND_MESSAGE) {
                    val message = intent.getStringExtra("message")
                    if (!message.isNullOrBlank() && webViewRef?.get() != null) {
                        if (message.contains("onEvent('ended')") ||
                            message.contains("onEvent(\"ended\")")
                        ) {
                            module.info("Emby LocalBroadcast ended → 直达 WebView（跳过 Activity 接收器）")
                        }
                        // Single delivery: wake + evaluateJavascript. Avoids double-ended
                        // when MainActivity receiver is still registered in background.
                        deliverToWebView(message)
                        return@intercept true
                    }
                }
                chain.proceed()
            }
            1
        }.onFailure {
            module.warn("LocalBroadcastManager.sendBroadcast Hook 失败: ${it.message}")
        }.getOrDefault(0)
    }

    private fun deliverToWebView(javascript: String) {
        val webView = webViewRef?.get() ?: return
        val runner = Runnable {
            wakeWebView(webView)
            runCatching {
                webView.evaluateJavascript(javascript, null)
            }.onFailure {
                module.warn("Emby 直达 evaluateJavascript 失败: ${it.message}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runner.run()
        } else {
            mainHandler.post(runner)
        }
    }

    private fun wakeWebView(webView: WebView?) {
        webView ?: return
        runCatching { webView.onResume() }
        runCatching {
            val resumeTimers = WebView::class.java.getMethod("resumeTimers")
            resumeTimers.invoke(null)
        }
    }

    companion object {
        private const val ACTION_SEND_MESSAGE = "com.mb.android.ACTION_SEND_MESSAGE"
    }
}
