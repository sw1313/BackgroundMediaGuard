package io.github.sw1313.backgroundmediaguard.xposed

import android.content.pm.ApplicationInfo
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * Allows protected media apps to call Service.startForeground() from the
 * background (Android 12+ BFGS). Emby hits this when JS auto-next tries to
 * re-enter MediaService FGS after the previous episode stopped.
 */
class FgsStartAllowHook(
    private val module: ModuleEntry,
    private val resolver: TargetResolver,
    private val registry: MediaProtectionRegistry,
) {
    private val recordFields = ConcurrentHashMap<Class<*>, RecordFields>()

    fun install(classLoader: ClassLoader) {
        var installed = 0

        val serviceRecord = runCatching {
            Class.forName("com.android.server.am.ServiceRecord", false, classLoader)
        }.getOrNull()
        if (serviceRecord != null) {
            serviceRecord.declaredMethods
                .filter {
                    it.name == "isFgsAllowedStart" &&
                        it.parameterCount == 0 &&
                        (it.returnType == Boolean::class.javaPrimitiveType ||
                            it.returnType == java.lang.Boolean::class.java)
                }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        module.hook(method).intercept { chain ->
                            val original = chain.proceed() as Boolean
                            if (original || !isProtectedRecord(chain.thisObject)) {
                                original
                            } else {
                                module.info("放行后台 startForeground：${describe(chain.thisObject)}")
                                true
                            }
                        }
                        installed++
                    }.onFailure {
                        module.warn("跳过 ServiceRecord.isFgsAllowedStart: ${it.message}")
                    }
                }
        } else {
            module.warn("找不到 ServiceRecord，跳过 isFgsAllowedStart Hook")
        }

        val activeServices = runCatching {
            Class.forName("com.android.server.am.ActiveServices", false, classLoader)
        }.getOrNull()
        if (activeServices != null) {
            activeServices.declaredMethods
                .filter {
                    it.name == "canStartForegroundServiceLocked" &&
                        it.parameterCount >= 3 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                        it.parameterTypes[2] == String::class.java
                }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        module.hook(method).intercept { chain ->
                            val original = chain.proceed() as Boolean
                            if (original) return@intercept true
                            val uid = chain.args[1] as Int
                            val packageName = chain.args[2] as String
                            if (resolver.shouldProtectPackage(packageName, uid, registry)) {
                                module.info("放行 canStartForegroundService：$packageName uid=$uid")
                                true
                            } else {
                                false
                            }
                        }
                        installed++
                    }.onFailure {
                        module.warn("跳过 ActiveServices.canStartForegroundServiceLocked: ${it.message}")
                    }
                }
        }

        if (installed == 0) {
            error("未安装任何 FGS 放行 Hook")
        }
        module.info("已安装后台 FGS 放行 Hook：$installed 个方法")
    }

    private fun isProtectedRecord(record: Any): Boolean {
        val fields = recordFields.computeIfAbsent(record.javaClass, ::findRecordFields)
        val appInfo = fields.appInfo?.getSafely(record) as? ApplicationInfo
        val packageName = appInfo?.packageName
            ?: fields.packageName?.getSafely(record)?.toString()
            ?: return false
        val uid = appInfo?.uid
            ?: fields.uid?.getIntSafely(record)
            ?: return false
        return resolver.shouldProtectPackage(packageName, uid, registry)
    }

    private fun describe(record: Any): String {
        val fields = recordFields.computeIfAbsent(record.javaClass, ::findRecordFields)
        val appInfo = fields.appInfo?.getSafely(record) as? ApplicationInfo
        val name = fields.shortInstanceName?.getSafely(record)?.toString()
            ?: appInfo?.packageName
            ?: "?"
        return name
    }

    private fun findRecordFields(clazz: Class<*>): RecordFields {
        var current: Class<*>? = clazz
        var appInfo: Field? = null
        var packageName: Field? = null
        var uid: Field? = null
        var shortInstanceName: Field? = null
        while (current != null && current != Any::class.java) {
            if (appInfo == null) {
                appInfo = runCatching {
                    current.getDeclaredField("appInfo").apply { isAccessible = true }
                }.getOrNull()
            }
            if (packageName == null) {
                packageName = runCatching {
                    current.getDeclaredField("packageName").apply { isAccessible = true }
                }.getOrNull()
            }
            if (uid == null) {
                uid = runCatching {
                    current.getDeclaredField("uid").apply { isAccessible = true }
                }.getOrNull()
            }
            if (shortInstanceName == null) {
                shortInstanceName = runCatching {
                    current.getDeclaredField("shortInstanceName").apply { isAccessible = true }
                }.getOrNull()
            }
            current = current.superclass
        }
        return RecordFields(appInfo, packageName, uid, shortInstanceName)
    }

    private fun Field.getSafely(target: Any): Any? = runCatching {
        isAccessible = true
        get(target)
    }.getOrNull()

    private fun Field.getIntSafely(target: Any): Int? = runCatching {
        isAccessible = true
        getInt(target)
    }.getOrNull()

    private data class RecordFields(
        val appInfo: Field?,
        val packageName: Field?,
        val uid: Field?,
        val shortInstanceName: Field?,
    )
}
