package io.github.sw1313.backgroundmediaguard.xposed

import android.app.AppOpsManager
import java.lang.reflect.Field

class AudioHardeningHook(
    private val module: ModuleEntry,
    private val resolver: TargetResolver,
) {
    fun install(classLoader: ClassLoader) {
        val uidStateClass = Class.forName(
            "com.android.server.appop.AppOpsService\$UidState",
            false,
            classLoader,
        )
        val uidField = uidStateClass.getDeclaredField("uid").apply { isAccessible = true }
        val protectedOps = resolveAudioOps()
        if (protectedOps.isEmpty()) {
            module.warn("系统未暴露音频 AppOps 常量，跳过 AudioHardening Hook")
            return
        }

        val methods = uidStateClass.declaredMethods.filter {
            it.name == "evalMode" &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        }
        methods.forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val op = chain.args[0] as Int
                val uid = uidField.getInt(chain.thisObject)
                if (op in protectedOps && resolver.audioHardeningEnabled(uid)) {
                    AppOpsManager.MODE_ALLOWED
                } else {
                    chain.proceed()
                }
            }
        }
        module.info(
            "已安装 AudioHardening Hook：${methods.size} 个方法，AppOps=${protectedOps.sorted()}",
        )
    }

    private fun resolveAudioOps(): Set<Int> {
        val names = arrayOf(
            "OP_PLAY_AUDIO",
            "OP_TAKE_AUDIO_FOCUS",
            "OP_CONTROL_AUDIO_PARTIAL",
            "OP_CONTROL_AUDIO",
        )
        return names.mapNotNull { name ->
            readStaticInt(AppOpsManager::class.java, name)?.also {
                module.info("$name=$it")
            }
        }.toSet()
    }

    private fun readStaticInt(clazz: Class<*>, name: String): Int? =
        runCatching {
            val field: Field = clazz.getDeclaredField(name)
            field.isAccessible = true
            field.getInt(null)
        }.getOrNull()
}
