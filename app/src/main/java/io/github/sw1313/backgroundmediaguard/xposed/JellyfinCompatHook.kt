package io.github.sw1313.backgroundmediaguard.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 官方 Jellyfin Android 2.7.3（org.jellyfin.mobile）。
 *
 * 进程和界面由系统侧保住。剩下的问题在播放器里：界面看不见时解码器被收走，
 * 视频样本塞满缓冲，音频拿不到数据，进度卡住。
 *
 * MainActivity 完全看不见时，用 Jellyfin 自己的 clearSelectionAndDisableRendererByType
 * 关掉视频类型；回到前台对当前轨道参数做反操作 setTrackTypeDisabled(视频, false)。
 * 不碰播放、暂停、加载和页面切换。耳机 / 蓝牙断开时调用 Jellyfin 的 pause()。
 */
class JellyfinCompatHook(
    private val module: ModuleEntry,
    private val packageName: String,
) {
    private val players = CopyOnWriteArrayList<WeakReference<Any>>()

    private var appClassLoader: ClassLoader? = null

    /** 被本模块关掉视频类型的轨道选择器。只在主线程读写。 */
    private var hiddenSelector: WeakReference<Any>? = null

    private var staleFilesCleared = false

    @Volatile
    private var audioCallbacksRegistered = false

    @Volatile
    private var lastPauseElapsed = 0L

    fun install(classLoader: ClassLoader) {
        appClassLoader = classLoader
        var installed = 0
        installed += hookActivityLifecycle(classLoader)
        installed += hookPlayerViewModel(classLoader)
        if (installed == 0) {
            module.warn("未安装任何 Jellyfin Hook")
        } else {
            module.info("已为 $packageName 安装 Jellyfin 后台只放音频 / 耳机暂停：hooks=$installed")
        }
    }

    private fun hookActivityLifecycle(classLoader: ClassLoader): Int {
        val activityClass = Class.forName("android.app.Activity", false, classLoader)
        var count = 0
        count += hookMainActivityNoArg(activityClass, "onStart") { activity ->
            ensureAudioCallbacks(activity as Context)
            clearStaleFiles(activity)
            showVideo()
        }
        count += hookMainActivityNoArg(activityClass, "onStop") { activity ->
            if (invoke0(activity, "isChangingConfigurations") != true) hideVideo()
        }
        return count
    }

    private fun hookMainActivityNoArg(
        activityClass: Class<*>,
        name: String,
        after: (Any) -> Unit,
    ): Int {
        var count = 0
        activityClass.declaredMethods
            .filter { it.name == name && it.parameterCount == 0 }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        val activity = chain.thisObject
                        if (activity.javaClass.name == MAIN_ACTIVITY) {
                            runCatching { after(activity) }
                                .onFailure { module.warn("Jellyfin $name 处理失败: ${it.message}") }
                        }
                        result
                    }
                    count++
                }.onFailure { module.warn("Jellyfin $name Hook 失败: ${it.message}") }
            }
        return count
    }

    private fun hookPlayerViewModel(classLoader: ClassLoader): Int {
        val viewModelClass = runCatching {
            Class.forName(PLAYER_VIEW_MODEL, false, classLoader)
        }.getOrNull() ?: return 0
        var count = 0
        viewModelClass.declaredConstructors.forEach { ctor ->
            runCatching {
                ctor.isAccessible = true
                module.hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    players.add(WeakReference(chain.thisObject))
                    result
                }
                count++
            }.onFailure { module.warn("Jellyfin PlayerViewModel 构造 Hook 失败: ${it.message}") }
        }
        return count
    }

    private fun hideVideo() {
        if (hiddenSelector?.get() != null) return
        val viewModel = liveViewModel() ?: return
        val selector = readField(viewModel, "trackSelector") ?: return
        val disable = trackUtilsMethod("clearSelectionAndDisableRendererByType") ?: run {
            module.warn("Jellyfin：找不到 clearSelectionAndDisableRendererByType")
            return
        }
        if (parametersField(selector) == null) {
            module.warn("Jellyfin：认不出轨道参数，不关视频轨")
            return
        }
        disable.invoke(null, selector, TRACK_TYPE_VIDEO)
        hiddenSelector = WeakReference(selector)
        module.info("Jellyfin：界面不可见，关掉视频轨，只放音频")
    }

    private fun showVideo() {
        val selector = hiddenSelector?.get() ?: return
        hiddenSelector = null
        val field = parametersField(selector) ?: run {
            module.warn("Jellyfin：认不出轨道参数，视频轨没打开")
            return
        }
        val current = field.get(selector) ?: return
        if (videoTypeDisabled(current) == false) return
        if (enableVideoType(selector, field, current)) {
            module.info("Jellyfin：回到前台，打开视频轨")
        } else {
            module.warn("Jellyfin：打开视频轨失败")
        }
    }

    /**
     * buildUpon().setTrackTypeDisabled(视频, false).build() 后交给轨道选择器。
     * media3 被混淆，build() 声明成返回父类，Builder 上还有同形的 setRendererDisabled，
     * 所以逐个试，并以禁用列表里不再有视频为准。
     */
    private fun enableVideoType(selector: Any, field: Field, current: Any): Boolean {
        val buildUpon = buildUponMethod(current.javaClass) ?: return false
        val builderClass = buildUpon.invoke(current)?.javaClass ?: return false
        val build = buildMethod(builderClass, current.javaClass) ?: return false
        for (toggle in builderToggles(builderClass)) {
            val builder = buildUpon.invoke(current) ?: continue
            if (runCatching { toggle.invoke(builder, TRACK_TYPE_VIDEO, false) }.isFailure) continue
            val built = runCatching { build.invoke(builder) }.getOrNull() ?: continue
            if (built.javaClass != current.javaClass) continue
            if (videoTypeDisabled(built) != false) continue
            val setter = findSetters(selector.javaClass, built.javaClass).firstOrNull() ?: return false
            setter.invoke(selector, built)
            return field.get(selector) === built
        }
        return false
    }

    /** 轨道选择器里能 buildUpon 的那个字段，就是轨道参数。 */
    private fun parametersField(selector: Any): Field? =
        instanceFields(selector.javaClass).firstOrNull { field ->
            val value = runCatching { field.get(selector) }.getOrNull() ?: return@firstOrNull false
            buildUponMethod(value.javaClass) != null && videoTypeDisabled(value) != null
        }

    private fun buildUponMethod(type: Class<*>): Method? =
        type.methods.firstOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                builderToggles(method.returnType).isNotEmpty()
        }?.apply { isAccessible = true }

    private fun buildMethod(builderClass: Class<*>, parametersClass: Class<*>): Method? =
        builderClass.methods.firstOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                method.returnType != Any::class.java &&
                method.returnType.isAssignableFrom(parametersClass)
        }?.apply { isAccessible = true }

    /** Builder 上返回 Builder 的 (int, boolean) 方法。 */
    private fun builderToggles(type: Class<*>): List<Method> {
        if (type.isPrimitive || type == Void.TYPE || type == Any::class.java) return emptyList()
        return type.methods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.parameterTypes[1] == java.lang.Boolean.TYPE &&
                method.returnType != Void.TYPE &&
                method.returnType.isAssignableFrom(type)
        }.onEach { it.isAccessible = true }
    }

    /** 读轨道参数里唯一的 Set 字段（disabledTrackTypes）。认不出时返回 null。 */
    private fun videoTypeDisabled(parameters: Any): Boolean? {
        var clazz: Class<*>? = parameters.javaClass
        var found: Set<*>? = null
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (!Set::class.java.isAssignableFrom(field.type)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(parameters) as? Set<*>
                }.getOrNull() ?: continue
                if (found != null) return null
                found = value
            }
            clazz = clazz.superclass
        }
        return found?.contains(TRACK_TYPE_VIDEO)
    }

    private fun trackUtilsMethod(name: String): Method? {
        val classLoader = appClassLoader ?: return null
        val utils = runCatching {
            Class.forName(TRACK_UTILS, false, classLoader)
        }.getOrNull() ?: return null
        return utils.declaredMethods.firstOrNull {
            it.name == name && it.parameterCount == 2 && Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }
    }

    private fun instanceFields(start: Class<*>): List<Field> {
        val result = mutableListOf<Field>()
        var clazz: Class<*>? = start
        while (clazz != null && clazz != Any::class.java) {
            clazz.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
                .forEach { field ->
                    runCatching { field.isAccessible = true }.onSuccess { result += field }
                }
            clazz = clazz.superclass
        }
        return result
    }

    private fun findSetters(start: Class<*>, valueClass: Class<*>): List<Method> {
        val exact = mutableListOf<Method>()
        val wider = mutableListOf<Method>()
        var clazz: Class<*>? = start
        while (clazz != null && clazz != Any::class.java) {
            clazz.declaredMethods.forEach { method ->
                if (Modifier.isStatic(method.modifiers)) return@forEach
                if (method.parameterCount != 1 || method.returnType != Void.TYPE) return@forEach
                val type = method.parameterTypes[0]
                if (type == Any::class.java || type.isInterface || type.isPrimitive) return@forEach
                if (!type.isAssignableFrom(valueClass)) return@forEach
                method.isAccessible = true
                if (type == valueClass) exact += method else wider += method
            }
            clazz = clazz.superclass
        }
        return exact + wider
    }

    private fun clearStaleFiles(activity: Any) {
        if (staleFilesCleared) return
        staleFilesCleared = true
        val dir = (activity as Context).applicationContext.filesDir
        STALE_FILES.forEach { File(dir, it).delete() }
    }

    private fun ensureAudioCallbacks(context: Context) {
        if (audioCallbacksRegistered) return
        val appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    pausePlayers("becoming-noisy")
                }
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        audioCallbacksRegistered = true
        module.info("Jellyfin：已监听耳机断开")
    }

    private fun pausePlayers(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPauseElapsed < 800L) return
        val targets = players.mapNotNull { it.get() }
        players.removeAll { it.get() == null }
        if (targets.isEmpty()) return
        lastPauseElapsed = now
        var paused = 0
        targets.forEach { viewModel ->
            if (playerOf(viewModel) == null) return@forEach
            if (call(viewModel, "pause")) paused++
        }
        if (paused > 0) {
            module.info("Jellyfin：耳机断开，已暂停播放（$reason）")
        }
    }

    private fun liveViewModel(): Any? {
        players.removeAll { it.get() == null }
        return players.map { it.get() }.lastOrNull { playerOf(it) != null }
    }

    private fun playerOf(viewModel: Any?): Any? = invoke0(viewModel, "getPlayerOrNull")

    private fun readField(target: Any, name: String): Any? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null && clazz != Any::class.java) {
            val field = runCatching { clazz.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull()
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun call(target: Any?, name: String): Boolean {
        target ?: return false
        return runCatching {
            val method = target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            } ?: return false
            method.invoke(target)
            true
        }.getOrDefault(false)
    }

    private fun invoke0(target: Any?, name: String): Any? {
        target ?: return null
        return runCatching {
            target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            }?.invoke(target)
        }.getOrNull()
    }

    companion object {
        private const val MAIN_ACTIVITY = "org.jellyfin.mobile.MainActivity"
        private const val PLAYER_VIEW_MODEL = "org.jellyfin.mobile.player.PlayerViewModel"
        private const val TRACK_UTILS = "org.jellyfin.mobile.utils.TrackSelectionUtilsKt"
        private const val TRACK_TYPE_VIDEO = 2
        private val STALE_FILES = listOf("bmg-jellyfin-player.bin", "bmg-jellyfin-player.txt")
    }
}
