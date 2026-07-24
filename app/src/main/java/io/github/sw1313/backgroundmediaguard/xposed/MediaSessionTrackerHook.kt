package io.github.sw1313.backgroundmediaguard.xposed

import android.media.session.PlaybackState
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class MediaSessionTrackerHook(
    private val module: ModuleEntry,
    private val registry: MediaProtectionRegistry,
) {
    fun install(classLoader: ClassLoader) {
        val recordClass = Class.forName(
            "com.android.server.media.MediaSessionRecord",
            false,
            classLoader,
        )
        val accessor = SessionAccessor(recordClass)
        var stateHooks = 0
        var destroyHooks = 0

        val stubClass = runCatching {
            Class.forName(
                "com.android.server.media.MediaSessionRecord\$SessionStub",
                false,
                classLoader,
            )
        }.getOrNull()
        if (stubClass != null) {
            stateHooks += hookSetPlaybackState(stubClass, accessor, useOuter = true)
        }
        stateHooks += hookSetPlaybackState(recordClass, accessor, useOuter = false)

        collectConcreteMethods(recordClass)
            .filter { it.parameterCount == 0 && it.name in setOf("destroySession", "close") }
            .forEach { method ->
                val ok = runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        runCatching { registry.remove(chain.thisObject) }
                        chain.proceed()
                    }
                    true
                }.onFailure {
                    module.warn(
                        "跳过无法 Hook 的方法 ${method.declaringClass.name}.${method.name}: ${it.message}",
                    )
                }.getOrDefault(false)
                if (ok) destroyHooks++
            }

        val serviceHooks = installServiceFallback(classLoader, accessor)
        if (stateHooks == 0 && serviceHooks == 0) {
            error("找不到可 Hook 的 MediaSession 播放状态入口")
        }
        module.info(
            "已安装 MediaSession 跟踪 Hook：状态方法=$stateHooks，销毁方法=$destroyHooks，服务入口=$serviceHooks",
        )
    }

    private fun hookSetPlaybackState(
        clazz: Class<*>,
        accessor: SessionAccessor,
        useOuter: Boolean,
    ): Int {
        var hooks = 0
        collectConcreteMethods(clazz)
            .filter { method ->
                method.name == "setPlaybackState" &&
                    method.parameterTypes.any { it.name == PlaybackState::class.java.name }
            }
            .forEach { method ->
                val stateIndex = method.parameterTypes.indexOfFirst {
                    it.name == PlaybackState::class.java.name
                }
                val ok = runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val record = if (useOuter) {
                                outerInstance(chain.thisObject) ?: chain.thisObject
                            } else {
                                chain.thisObject
                            }
                            val state = chain.args[stateIndex] as? PlaybackState
                            applyState(record, accessor, state)
                        }.onFailure {
                            module.warn("MediaSession 状态回调处理失败: ${it.message}")
                        }
                        result
                    }
                    true
                }.onFailure {
                    module.warn(
                        "跳过无法 Hook 的方法 ${method.declaringClass.name}.${method.name}: ${it.message}",
                    )
                }.getOrDefault(false)
                if (ok) hooks++
            }
        return hooks
    }

    private fun installServiceFallback(
        classLoader: ClassLoader,
        accessor: SessionAccessor,
    ): Int {
        val serviceClass = runCatching {
            Class.forName("com.android.server.media.MediaSessionService", false, classLoader)
        }.getOrNull() ?: return 0
        var hooks = 0
        collectConcreteMethods(serviceClass)
            .filter { method ->
                method.name == "onSessionPlaybackStateChanged" &&
                    method.parameterCount >= 1 &&
                    method.parameterTypes[0].name.contains("MediaSessionRecord")
            }
            .forEach { method ->
                val stateIndex = method.parameterTypes.indexOfFirst {
                    it.name == PlaybackState::class.java.name
                }
                val ok = runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val record = chain.args[0]
                            val state = if (stateIndex >= 0) {
                                chain.args[stateIndex] as? PlaybackState
                            } else {
                                runCatching {
                                    record.javaClass.methods.firstOrNull {
                                        it.name == "getPlaybackState" && it.parameterCount == 0
                                    }?.invoke(record) as? PlaybackState
                                }.getOrNull()
                            }
                            applyState(record, accessor, state)
                        }.onFailure {
                            module.warn("MediaSession 服务入口处理失败: ${it.message}")
                        }
                        result
                    }
                    true
                }.onFailure {
                    module.warn(
                        "跳过无法 Hook 的方法 ${method.declaringClass.name}.${method.name}: ${it.message}",
                    )
                }.getOrDefault(false)
                if (ok) hooks++
            }
        return hooks
    }

    private fun applyState(
        record: Any,
        accessor: SessionAccessor,
        state: PlaybackState?,
    ) {
        val packageName = accessor.packageName(record)
        val uid = accessor.uid(record)
        if (packageName != null && uid != null) {
            registry.update(record, packageName, uid, state.isActiveMediaState())
        } else {
            registry.remove(record)
        }
    }

    private fun outerInstance(inner: Any): Any? {
        val field = inner.javaClass.declaredFields.firstOrNull {
            it.name.startsWith("this$")
        } ?: return null
        field.isAccessible = true
        return runCatching { field.get(inner) }.getOrNull()
    }

    private fun collectConcreteMethods(clazz: Class<*>): List<Method> {
        val methods = mutableListOf<Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            methods += current.declaredMethods.filter { !Modifier.isAbstract(it.modifiers) }
            current = current.superclass
        }
        return methods
    }

    private fun PlaybackState?.isActiveMediaState(): Boolean = when (this?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING -> true
        else -> false
    }

    private class SessionAccessor(clazz: Class<*>) {
        private val packageGetter = findMethod(clazz, "getPackageName")
        private val uidGetter = findMethod(clazz, "getUid")
        private val packageField = findField(clazz, "mPackageName", "packageName")
        private val uidField = findField(clazz, "mOwnerUid", "mUid", "uid")

        fun packageName(record: Any): String? =
            runCatching { packageGetter?.invoke(record) as? String }.getOrNull()
                ?: runCatching { packageField?.get(record) as? String }.getOrNull()

        fun uid(record: Any): Int? =
            runCatching { (uidGetter?.invoke(record) as? Number)?.toInt() }.getOrNull()
                ?: runCatching { (uidField?.get(record) as? Number)?.toInt() }.getOrNull()

        companion object {
            private fun findMethod(clazz: Class<*>, name: String): Method? =
                clazz.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.apply {
                    isAccessible = true
                }

            private fun findField(clazz: Class<*>, vararg names: String): Field? {
                var current: Class<*>? = clazz
                while (current != null) {
                    val type = current
                    names.forEach { name ->
                        runCatching { type.getDeclaredField(name) }.getOrNull()?.let {
                            it.isAccessible = true
                            return it
                        }
                    }
                    current = type.superclass
                }
                return null
            }
        }
    }
}
