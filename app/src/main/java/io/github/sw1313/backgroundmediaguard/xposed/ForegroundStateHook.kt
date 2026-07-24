package io.github.sw1313.backgroundmediaguard.xposed

import android.content.pm.ServiceInfo
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

class ForegroundStateHook(
    private val module: ModuleEntry,
    private val resolver: TargetResolver,
    private val registry: MediaProtectionRegistry,
) {
    private val appFields = ConcurrentHashMap<Class<*>, Field?>()

    fun install(classLoader: ClassLoader) {
        val stateClasses = sequenceOf(
            "com.android.server.am.psc.ProcessServiceRecordInternal",
            "com.android.server.am.ProcessServiceRecord",
        ).mapNotNull { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()
        }.distinct().toList()
        if (stateClasses.isEmpty()) error("找不到 ProcessServiceRecord")

        var installed = 0
        stateClasses.forEach { stateClass ->
            stateClass.declaredMethods.filter { method ->
                when (method.name) {
                    "hasForegroundServices",
                    "hasReportedForegroundServices",
                    "hasNonShortForegroundServices",
                    "getForegroundServiceTypes" -> method.parameterCount == 0
                    "containsAnyForegroundServiceTypes" -> method.parameterCount == 1
                    else -> false
                }
            }.forEach { method ->
                method.isAccessible = true
                runCatching {
                    module.hook(method).intercept { chain ->
                        val original = chain.proceed()
                        if (!isProtected(chain.thisObject)) {
                            original
                        } else {
                            when (method.name) {
                                "getForegroundServiceTypes" ->
                                    (original as Int) or
                                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                                "containsAnyForegroundServiceTypes" -> {
                                    val requested = chain.args[0] as Int
                                    (original as Boolean) ||
                                        requested and
                                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0
                                }
                                else -> true
                            }
                        }
                    }
                }.onSuccess {
                    installed++
                }.onFailure {
                    module.warn("跳过媒体前台状态方法 ${stateClass.name}#${method.name}: $it")
                }
            }
        }
        module.info(
            "已安装媒体前台状态 Hook：$installed 个方法，类=${stateClasses.joinToString { it.name }}",
        )
    }

    private fun isProtected(serviceState: Any): Boolean {
        val field = appFields.computeIfAbsent(serviceState.javaClass, ::findAppField)
        val process = runCatching { field?.get(serviceState) }.getOrNull() ?: return false
        return resolver.shouldProtectProcess(process, registry)
    }

    private fun findAppField(clazz: Class<*>): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            val type = current
            runCatching { type.getDeclaredField("mApp") }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            current = type.superclass
        }
        return null
    }
}
