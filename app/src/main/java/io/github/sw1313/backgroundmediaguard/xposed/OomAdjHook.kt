package io.github.sw1313.backgroundmediaguard.xposed

import java.lang.reflect.Field

class OomAdjHook(
    private val module: ModuleEntry,
    private val resolver: TargetResolver,
    private val registry: MediaProtectionRegistry,
) {
    fun install(classLoader: ClassLoader) {
        val internalCount = installProcessStateHooks(classLoader)
        val lmkdCount = installLmkdHook(classLoader)
        module.info("已安装 OOM adj Hook：内部状态=$internalCount，LMKD=$lmkdCount")
    }

    private fun installProcessStateHooks(classLoader: ClassLoader): Int {
        val stateClass = Class.forName(
            "com.android.server.am.ProcessStateRecord",
            false,
            classLoader,
        )
        val appField = findField(stateClass, "mApp")
            ?: error("ProcessStateRecord.mApp 不存在")
        val methods = stateClass.declaredMethods.filter {
            it.name in setOf("setCurRawAdj", "setSetRawAdj", "setCurAdj", "setSetAdj") &&
                it.parameterCount >= 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        methods.forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val process = runCatching { appField.get(chain.thisObject) }.getOrNull()
                val adj = chain.args[0] as Int
                val adjusted = clampAdj(
                    adj,
                    resolver.shouldProtectProcess(process, registry),
                )
                if (adjusted != adj) {
                    val args = chain.args.toTypedArray()
                    args[0] = adjusted
                    chain.proceed(args)
                } else {
                    chain.proceed()
                }
            }
        }
        return methods.size
    }

    private fun installLmkdHook(classLoader: ClassLoader): Int {
        val processList = Class.forName(
            "com.android.server.am.ProcessList",
            false,
            classLoader,
        )
        val methods = processList.declaredMethods.filter {
            it.name == "setOomAdj" &&
                it.parameterCount >= 3 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType
        }
        methods.forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val uid = chain.args[1] as Int
                val adj = chain.args[2] as Int
                val adjusted = clampAdj(adj, resolver.shouldProtectUid(uid, registry))
                if (adjusted != adj) {
                    val args = chain.args.toTypedArray()
                    args[2] = adjusted
                    chain.proceed(args)
                } else {
                    chain.proceed()
                }
            }
        }
        return methods.size
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            val type = current
            runCatching { type.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            current = type.superclass
        }
        return null
    }

    companion object {
        internal const val PERCEPTIBLE_APP_ADJ = 200

        internal fun clampAdj(adj: Int, protected: Boolean): Int =
            if (protected) adj.coerceAtMost(PERCEPTIBLE_APP_ADJ) else adj
    }
}
