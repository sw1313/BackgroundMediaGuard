package io.github.sw1313.backgroundmediaguard.xposed

import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

class LowMemDestroyHook(
    private val module: ModuleEntry,
    private val resolver: TargetResolver,
    private val registry: MediaProtectionRegistry,
) {
    private val lastSkipAt = ConcurrentHashMap<String, Long>()

    fun install(classLoader: ClassLoader) {
        val recordClass = Class.forName(
            "com.android.server.wm.ActivityRecord",
            false,
            classLoader,
        )
        val packageField = recordClass.getDeclaredField("packageName").apply { isAccessible = true }
        val methods = recordClass.declaredMethods.filter {
            it.name in DESTROY_METHODS &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java &&
                it.returnType == java.lang.Boolean.TYPE
        }
        if (methods.isEmpty()) error("找不到 ActivityRecord 的 low-mem 销毁入口")
        methods.forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val reason = chain.args.getOrNull(0) as? String
                if (reason == LOW_MEM && shouldKeep(chain.thisObject, packageField, "low-mem 销毁")) {
                    false
                } else {
                    chain.proceed()
                }
            }
        }
        val relaunch = installAssetsPathRelaunchHook(recordClass, packageField)
        module.info("已安装界面保活：销毁=${methods.size}，配置重载=$relaunch")
    }

    private fun installAssetsPathRelaunchHook(recordClass: Class<*>, packageField: Field): Int {
        val method = recordClass.declaredMethods.firstOrNull {
            it.name == "shouldRelaunchLocked" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.returnType == java.lang.Boolean.TYPE
        } ?: error("找不到 ActivityRecord.shouldRelaunchLocked")
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val changes = chain.args[0] as Int
            if (changes and CONFIG_ASSETS_PATHS != 0 &&
                shouldKeep(chain.thisObject, packageField, "assetsPaths 重载")
            ) {
                val rest = changesWithoutAssetsPaths(changes)
                if (rest == 0) {
                    false
                } else {
                    val args = chain.args.toTypedArray()
                    args[0] = rest
                    chain.proceed(args)
                }
            } else {
                chain.proceed()
            }
        }
        return 1
    }

    private fun shouldKeep(record: Any?, packageField: Field, reason: String): Boolean {
        val packageName = runCatching { packageField.get(record) as? String }.getOrNull() ?: return false
        if (!resolver.isEnabledTarget(packageName)) return false
        if (!registry.retainsActivity(packageName, RETAIN_AFTER_PLAYBACK_MS)) return false
        val now = System.currentTimeMillis()
        val key = "$reason:$packageName"
        val previous = lastSkipAt[key] ?: 0L
        if (now - previous > SKIP_LOG_INTERVAL_MS) {
            lastSkipAt[key] = now
            module.info("跳过 $reason $packageName")
        }
        return true
    }

    companion object {
        internal const val CONFIG_ASSETS_PATHS = 0x80000000.toInt()

        internal fun changesWithoutAssetsPaths(changes: Int): Int =
            changes and CONFIG_ASSETS_PATHS.inv()

        private const val LOW_MEM = "low-mem"
        private val DESTROY_METHODS = setOf("destroyIfPossible", "destroyImmediately")
        private const val RETAIN_AFTER_PLAYBACK_MS = 30 * 60 * 1000L
        private const val SKIP_LOG_INTERVAL_MS = 30_000L
    }
}
