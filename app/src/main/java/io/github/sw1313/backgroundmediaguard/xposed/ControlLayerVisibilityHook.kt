package io.github.sw1313.backgroundmediaguard.xposed

import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Keeps hybrid-app control layers schedulable in the background.
 *
 * For WebView/RN media clients, pretend the control layer stays VISIBLE and
 * skip host onPause so JS can still decide auto-next. Requires the target
 * package to be in the LSPosed module scope.
 *
 * Note: libxposed [Chain.args] is immutable — never assign into it; use
 * [io.github.libxposed.api.XposedInterface.Chain.proceed] with a new array.
 */
class ControlLayerVisibilityHook(
    private val module: ModuleEntry,
    private val packageName: String,
) {
    fun install(classLoader: ClassLoader) {
        var webViewHooks = 0
        var rnHooks = 0

        runCatching {
            val webViewClass = Class.forName("android.webkit.WebView", false, classLoader)
            webViewHooks += hookVisibility(webViewClass)
            webViewHooks += hookNoArgSkip(webViewClass, "onPause")
            webViewHooks += hookNoArgSkip(webViewClass, "pauseTimers")
        }.onFailure {
            module.warn("$packageName WebView 控制层 Hook 失败: ${it.message}")
        }

        // Plex-style React Native: host pause freezes the JS runtime that decides nextTrack.
        listOf(
            "com.facebook.react.ReactInstanceManager",
            "com.facebook.react.runtime.ReactHostImpl",
        ).forEach { name ->
            val clazz = runCatching {
                Class.forName(name, false, classLoader)
            }.getOrNull() ?: return@forEach
            rnHooks += hookNoArgSkip(clazz, "onHostPause")
        }

        if (webViewHooks == 0 && rnHooks == 0) {
            error("未找到可 Hook 的控制层入口（WebView / React Native）")
        }
        module.info(
            "已为 $packageName 安装控制层可见性保持：WebView=$webViewHooks，RN=$rnHooks",
        )
    }

    private fun hookVisibility(webViewClass: Class<*>): Int {
        var count = 0
        concreteMethods(webViewClass)
            .filter {
                it.name == "onWindowVisibilityChanged" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            .forEach { method ->
                val ok = runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val visibility = chain.args[0] as Int
                        if (visibility != View.VISIBLE) {
                            // Rewrite via proceed(newArgs); never mutate chain.args.
                            chain.proceed(arrayOf(View.VISIBLE))
                        } else {
                            chain.proceed()
                        }
                    }
                    true
                }.onFailure {
                    module.warn(
                        "跳过 ${method.declaringClass.name}.${method.name}: ${it.message}",
                    )
                }.getOrDefault(false)
                if (ok) count++
            }
        return count
    }

    private fun hookNoArgSkip(clazz: Class<*>, methodName: String): Int {
        var count = 0
        concreteMethods(clazz)
            .filter { it.name == methodName && it.parameterCount == 0 }
            .forEach { method ->
                val ok = runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        // Do not call proceed(): keep timers/bridge active while backgrounded.
                        null
                    }
                    true
                }.onFailure {
                    module.warn(
                        "跳过 ${method.declaringClass.name}.${method.name}: ${it.message}",
                    )
                }.getOrDefault(false)
                if (ok) count++
            }
        return count
    }

    private fun concreteMethods(clazz: Class<*>): List<Method> {
        val methods = mutableListOf<Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            methods += current.declaredMethods.filter { !Modifier.isAbstract(it.modifiers) }
            current = current.superclass
        }
        return methods
    }
}
