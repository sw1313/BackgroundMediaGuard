package io.github.sw1313.backgroundmediaguard.xposed

import android.content.Context
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.sw1313.backgroundmediaguard.Settings

class ModuleEntry : XposedModule() {
    @Volatile
    private var hyperOsHookInstalled = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        info(
            "模块加载于 ${param.processName}；框架=$frameworkName $frameworkVersion，API=$apiVersion",
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (frameworkProperties and PROP_CAP_SYSTEM == 0L) {
            warn("当前框架不支持 system_server Hook")
            return
        }
        if (frameworkProperties and PROP_CAP_REMOTE == 0L) {
            warn("当前框架不支持 Remote Preferences，无法安全读取按应用配置")
            return
        }

        val context = runCatching { obtainSystemContext(param.classLoader) }
            .onFailure { error("无法取得 system_server Context", it) }
            .getOrNull() ?: return
        val resolver = TargetResolver(context, getRemotePreferences(Settings.GROUP))
        val registry = MediaProtectionRegistry()

        installSafely("ProcessRecord 跟踪") {
            ProcessTrackerHook(this, resolver).install(param.classLoader)
        }
        installSafely("MediaSession 跟踪") {
            MediaSessionTrackerHook(this, registry).install(param.classLoader)
        }
        installSafely("AudioHardening") {
            AudioHardeningHook(this, resolver).install(param.classLoader)
        }
        installSafely("媒体前台状态") {
            ForegroundStateHook(this, resolver, registry).install(param.classLoader)
        }
        installSafely("后台 FGS 放行") {
            FgsStartAllowHook(this, resolver, registry).install(param.classLoader)
        }
        installSafely("LMKD OOM adj") {
            OomAdjHook(this, resolver, registry).install(param.classLoader)
        }
        installSafely("缓存冻结保护") {
            FreezerHook(this, resolver, registry).install(param.classLoader)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName == HYPEROS_POWERKEEPER && !hyperOsHookInstalled) {
            if (frameworkProperties and PROP_CAP_REMOTE == 0L) {
                warn("框架不支持 Remote Preferences，跳过 HyperOS 零数据暂停保护")
            } else {
                val context = runCatching { obtainSystemContext(param.classLoader) }
                    .onFailure { error("PowerKeeper 进程中无法取得 Context", it) }
                    .getOrNull()
                if (context != null) {
                    val resolver = TargetResolver(context, getRemotePreferences(Settings.GROUP))
                    installSafely("HyperOS 零数据暂停保护") {
                        HyperOsZeroDataHook(this, resolver).install(param.classLoader)
                        hyperOsHookInstalled = true
                    }
                }
            }
        }

        installAppScopedHooksIfNeeded(param)
    }

    private fun installAppScopedHooksIfNeeded(param: PackageReadyParam) {
        if (frameworkProperties and PROP_CAP_REMOTE == 0L) return
        val packageName = param.packageName ?: return
        if (packageName == HYPEROS_POWERKEEPER || packageName == "android") return

        val context = runCatching { obtainSystemContext(param.classLoader) }.getOrNull() ?: return
        val resolver = TargetResolver(context, getRemotePreferences(Settings.GROUP))
        val uid = Process.myUid()

        if (resolver.shouldKeepControlLayer(packageName, uid)) {
            installSafely("控制层可见性保持 ($packageName)") {
                ControlLayerVisibilityHook(this, packageName).install(param.classLoader)
            }
        }
        if (resolver.shouldUseJsBridge(packageName, uid)) {
            installSafely("片尾 JS 桥接 ($packageName)") {
                EmbyJsBridgeHook(this, packageName).install(param.classLoader)
            }
        }

        val plexPip = resolver.plexPipKeepPlaying(packageName, uid)
        val plexNotification = resolver.plexMediaNotificationFix(packageName, uid)
        val plexSurface = resolver.plexSurfaceRestore(packageName, uid)
        if (plexPip || plexNotification || plexSurface) {
            installSafely("Plex 兼容修复") {
                PlexCompatHook(
                    this,
                    packageName,
                    pipKeepPlaying = plexPip,
                    mediaNotificationFix = plexNotification,
                    surfaceRestore = plexSurface,
                ).install(param.classLoader)
            }
        }
    }

    private fun obtainSystemContext(classLoader: ClassLoader): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread", false, classLoader)
        val current = activityThreadClass.getDeclaredMethod("currentActivityThread").apply {
            isAccessible = true
        }.invoke(null) ?: error("ActivityThread.currentActivityThread() 返回 null")
        return activityThreadClass.getDeclaredMethod("getSystemContext").apply {
            isAccessible = true
        }.invoke(current) as Context
    }

    private inline fun installSafely(name: String, block: () -> Unit) {
        runCatching(block).onFailure { error("$name Hook 安装失败，已跳过", it) }
    }

    fun info(message: String) = log(Log.INFO, TAG, message)
    fun warn(message: String) = log(Log.WARN, TAG, message)
    fun error(message: String, throwable: Throwable) = log(Log.ERROR, TAG, message, throwable)

    companion object {
        const val TAG = "BackgroundMediaGuard"
        private const val HYPEROS_POWERKEEPER = "com.miui.powerkeeper"
    }
}
