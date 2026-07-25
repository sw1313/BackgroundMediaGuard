package io.github.sw1313.backgroundmediaguard.xposed

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

/**
 * Plex 兼容（精简版）。
 *
 * 开关：
 * - pipKeepPlaying：关画中画时跳过 Stop
 * - mediaNotificationFix：通知可点回 + 进度
 * - surfaceRestore：后台切集后轻量重绑画面（交给 Plex surfaceCreated 为主）
 * - preventRestart：只做三件事——记进度、拦底层误 seek→0、回前台走引擎 H0()/play()
 *
 * 刻意不做：禁视频轨、吞 s2、watchdog、多层 recover/prepare、主动 clearVideoSurface。
 */
class PlexCompatHook(
    private val module: ModuleEntry,
    private val packageName: String,
    private val pipKeepPlaying: Boolean,
    private val mediaNotificationFix: Boolean,
    private val surfaceRestore: Boolean,
    private val preventRestart: Boolean,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var suppressStopCommand = false

    @Volatile
    private var engineManagerRef: WeakReference<Any>? = null

    @Volatile
    private var engineRef: WeakReference<Any>? = null

    @Volatile
    private var exoPlayerRef: WeakReference<Any>? = null

    @Volatile
    private var sessionPlayerHooked = false

    @Volatile
    private var loggedProgressBypass = false

    @Volatile
    private var savedPositionMs = 0L

    @Volatile
    private var protectPositionUntilElapsed = 0L

    @Volatile
    private var screenReceiverRegistered = false

    @Volatile
    private var allowProtectedSeek = false

    fun install(classLoader: ClassLoader) {
        var installed = 0
        if (pipKeepPlaying) {
            installed += installPipKeepPlaying(classLoader)
        }
        if (mediaNotificationFix) {
            installed += installNotificationBringToFront(classLoader)
            installed += installSessionPlayerProgress(classLoader)
        } else if (preventRestart) {
            installed += installBringToFrontIntentRewrite(classLoader)
        }
        if (surfaceRestore) {
            installed += installSurfaceRestore(classLoader)
        }
        if (preventRestart) {
            installed += installEngineRemember(classLoader)
            installed += installPreventRestart(classLoader)
        }
        if (installed == 0) {
            error("未安装任何 Plex 兼容 Hook")
        }
        module.info(
            "已为 $packageName 安装 Plex 兼容修复：hooks=$installed " +
                "(pip=$pipKeepPlaying, notification=$mediaNotificationFix, " +
                "surface=$surfaceRestore, preventRestart=$preventRestart)",
        )
    }

    private fun installPipKeepPlaying(classLoader: ClassLoader): Int {
        var count = 0
        val activityClass = runCatching {
            Class.forName("tv.plex.app.MainActivity", false, classLoader)
        }.getOrNull()
        if (activityClass != null) {
            activityClass.declaredMethods
                .filter {
                    it.name == "onPictureInPictureModeChanged" &&
                        it.parameterCount >= 1 &&
                        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        module.hook(method).intercept { chain ->
                            val inPip = chain.args[0] as Boolean
                            if (inPip) {
                                chain.proceed()
                            } else {
                                suppressStopCommand = true
                                try {
                                    chain.proceed()
                                } finally {
                                    suppressStopCommand = false
                                }
                            }
                        }
                        count++
                    }.onFailure {
                        module.warn("Plex PiP Hook 失败: ${it.message}")
                    }
                }
        }

        val eventsClass = runCatching {
            Class.forName("tv.plex.video.react.VideoEvents", false, classLoader)
        }.getOrNull()
        if (eventsClass != null) {
            eventsClass.declaredMethods
                .filter { it.name == "sendStopCommand" && it.parameterCount == 0 }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        module.hook(method).intercept { chain ->
                            if (suppressStopCommand) {
                                module.info("Plex：关闭画中画时跳过 Stop，保持后台播放")
                                null
                            } else {
                                chain.proceed()
                            }
                        }
                        count++
                    }.onFailure {
                        module.warn("Plex sendStopCommand Hook 失败: ${it.message}")
                    }
                }
        }
        return count
    }

    private fun installNotificationBringToFront(classLoader: ClassLoader): Int {
        var count = 0
        count += hookNotificationManagerNotify()
        count += installBringToFrontIntentRewrite(classLoader)
        runCatching {
            val method = MediaMetadata.Builder::class.java.getDeclaredMethod(
                "putLong",
                String::class.java,
                Long::class.javaPrimitiveType,
            )
            module.hook(method).intercept { chain ->
                val key = chain.args[0] as? String
                val value = chain.args[1] as? Long ?: 0L
                if (key == MediaMetadata.METADATA_KEY_DURATION && value <= 0L) {
                    val live = firstLong(resolveExoPlayer(null), "getDuration")
                    if (live != null && live > 0L) {
                        module.info("Plex：补全 MediaMetadata.DURATION=$live")
                        return@intercept chain.proceed(arrayOf(key, live))
                    }
                }
                chain.proceed()
            }
            count++
        }.onFailure {
            module.warn("Plex MediaMetadata.DURATION Hook 失败: ${it.message}")
        }
        return count
    }

    private fun installBringToFrontIntentRewrite(classLoader: ClassLoader): Int {
        var count = 0
        val getActivity = runCatching {
            PendingIntent::class.java.getDeclaredMethod(
                "getActivity",
                Context::class.java,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull()
        if (getActivity != null) {
            runCatching {
                module.hook(getActivity).intercept { chain ->
                    val context = chain.args[0] as? Context
                    val requestCode = chain.args[1] as Int
                    val original = chain.args[2] as? Intent
                    val flags = chain.args[3] as Int
                    if (context?.packageName == packageName &&
                        shouldRewriteSessionIntent(original, requestCode)
                    ) {
                        val fixed = buildBringToFrontIntent(packageName)
                        module.info("Plex：重写 session/通知拉起 Intent (req=$requestCode)")
                        chain.proceed(arrayOf(context, requestCode, fixed, flags))
                    } else {
                        chain.proceed()
                    }
                }
                count++
            }.onFailure {
                module.warn("Plex PendingIntent.getActivity Hook 失败: ${it.message}")
            }
        }

        runCatching {
            val method = Context::class.java.getDeclaredMethod(
                "getLaunchIntentForPackage",
                String::class.java,
            )
            module.hook(method).intercept { chain ->
                val result = chain.proceed() as? Intent
                val pkg = chain.args[0] as? String
                if (pkg == packageName) {
                    sanitizeBringToFrontFlags(result)
                } else {
                    result
                }
            }
            count++
        }.onFailure {
            module.warn("Plex getLaunchIntentForPackage Hook 失败: ${it.message}")
        }
        return count
    }

    /**
     * 精简防重启：
     * 1) 息屏只记进度 + 开短保护窗
     * 2) Hook T0.G.R1 拦误 seek→0
     * 3) onResume 调 of.k.H0()（与 EngineManager.resume 同源）再 play
     * 4) 可选吞系统注入 BACK
     */
    private fun installPreventRestart(classLoader: ClassLoader): Int {
        var count = 0
        val activityClass = runCatching {
            Class.forName("tv.plex.app.MainActivity", false, classLoader)
        }.getOrNull()
        if (activityClass == null) {
            module.warn("Plex preventRestart：未找到 MainActivity")
            return 0
        }

        activityClass.declaredMethods
            .filter {
                it.name == "dispatchKeyEvent" &&
                    it.parameterCount == 1 &&
                    KeyEvent::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val event = chain.args[0] as? KeyEvent
                        if (event != null && shouldSwallowSyntheticBack(event)) {
                            rememberPosition("synthetic-back")
                            armPositionProtect("synthetic-back")
                            module.info(
                                "Plex：忽略系统注入 BACK (action=${event.action}, deviceId=${event.deviceId})",
                            )
                            true
                        } else {
                            chain.proceed()
                        }
                    }
                    count++
                }
            }

        activityClass.declaredMethods
            .filter { it.name == "onCreate" }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        (chain.thisObject as? Activity)?.let {
                            registerScreenReceiver(it.applicationContext)
                        }
                        result
                    }
                    count++
                }
            }

        // 回前台：走 Plex 自己的 resume（H0），不要禁轨/绑面编排。
        activityClass.declaredMethods
            .filter { it.name == "onResume" && it.parameterCount == 0 }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        mainHandler.postDelayed({ resumeEnginePlayback("onResume") }, 280)
                        result
                    }
                    count++
                }
            }

        count += installExoSeekGuard(classLoader)
        return count
    }

    private fun installExoSeekGuard(classLoader: ClassLoader): Int {
        val gClass = runCatching {
            Class.forName("T0.G", false, classLoader)
        }.getOrNull() ?: return 0
        var count = 0
        gClass.declaredMethods
            .filter {
                it.name == "R1" &&
                    it.parameterCount >= 2 &&
                    it.parameterTypes[1] == Long::class.javaPrimitiveType
            }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val pos = (chain.args[1] as? Long) ?: 0L
                        if (shouldBlockSeekToStart(pos)) {
                            module.info("Plex：拦截 Exo seek→${pos}ms，保持 ${savedPositionMs}ms")
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                    count++
                }
            }
        if (count > 0) {
            module.info("Plex：已安装底层 seek 守护 hooks=$count")
        }
        return count
    }

    private fun installEngineRemember(classLoader: ClassLoader): Int {
        var count = 0
        val emClass = runCatching {
            Class.forName("tv.plex.video.react.EngineManager", false, classLoader)
        }.getOrNull() ?: return 0
        emClass.declaredConstructors.forEach { ctor ->
            runCatching {
                ctor.isAccessible = true
                module.hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    rememberEngineManager(chain.thisObject)
                    result
                }
                count++
            }
        }
        return count
    }

    private fun registerScreenReceiver(context: Context) {
        if (screenReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        rememberPosition("screen-off")
                        armPositionProtect("screen-off")
                        // 不再禁轨、不清 Surface、不 watchdog。让 Plex/Exo 自己处理卸面。
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        armPositionProtect("screen-on")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            screenReceiverRegistered = true
            module.info("Plex：已注册息屏进度保护（精简）")
        }.onFailure {
            module.warn("Plex 息屏广播注册失败: ${it.message}")
        }
    }

    /** 与 EngineManager.resume() → of.k.H0() 同源，并补 play。 */
    private fun resumeEnginePlayback(reason: String) {
        rememberPosition(reason)
        val engine = engineRef?.get()
        val calledH0 = runCatching {
            val method = engine?.javaClass?.methods?.firstOrNull {
                it.name == "H0" && it.parameterCount == 0
            } ?: return@runCatching false
            method.isAccessible = true
            method.invoke(engine)
            true
        }.getOrDefault(false)

        val exo = resolveExoPlayer(null)
        if (exo != null) {
            val live = firstLong(exo, "C1", "getCurrentPosition", "c1", "getContentPosition") ?: 0L
            if (savedPositionMs > 3000L && live < SEEK_ZERO_THRESHOLD_MS) {
                seekExoTo(exo, savedPositionMs)
            }
            ensurePlaying(exo)
        }

        // 引擎也可能暴露 resume()
        if (!calledH0) {
            runCatching {
                engineManagerRef?.get()?.javaClass?.methods
                    ?.firstOrNull { it.name == "resume" && it.parameterCount == 0 }
                    ?.also {
                        it.isAccessible = true
                        it.invoke(engineManagerRef?.get())
                    }
            }
        }

        module.info(
            "Plex：回前台续播（$reason, H0=$calledH0, pos=$savedPositionMs, " +
                "playing=${callBoolean(exo, "isPlaying")}）",
        )
    }

    private fun rememberPosition(reason: String) {
        val live = firstLong(resolveExoPlayer(null), "C1", "getCurrentPosition", "c1", "getContentPosition")
        if (live != null && live > 1000L) {
            savedPositionMs = live
            module.info("Plex：记住进度 ${savedPositionMs}ms（$reason）")
        }
    }

    private fun armPositionProtect(reason: String) {
        if (savedPositionMs <= 1000L) rememberPosition(reason)
        if (savedPositionMs <= 1000L) return
        protectPositionUntilElapsed = SystemClock.elapsedRealtime() + POSITION_PROTECT_MS
        module.info("Plex：进度保护窗 ${POSITION_PROTECT_MS}ms（$reason, pos=$savedPositionMs）")
    }

    private fun shouldBlockSeekToStart(targetMs: Long): Boolean {
        if (allowProtectedSeek) return false
        if (targetMs >= SEEK_ZERO_THRESHOLD_MS) return false
        if (savedPositionMs < 3000L) return false
        if (SystemClock.elapsedRealtime() > protectPositionUntilElapsed) return false
        val duration = firstLong(resolveExoPlayer(null), "getDuration") ?: 0L
        if (duration > 0L && savedPositionMs >= duration - NEAR_END_MS) return false
        return savedPositionMs - targetMs > 3000L
    }

    private fun seekExoTo(exo: Any, positionMs: Long): Boolean {
        allowProtectedSeek = true
        return try {
            runCatching {
                val method = exo.javaClass.methods.firstOrNull {
                    it.name == "seekTo" &&
                        it.parameterCount == 1 &&
                        it.parameterTypes[0] == Long::class.javaPrimitiveType
                } ?: return@runCatching false
                method.invoke(exo, positionMs)
                true
            }.getOrDefault(false)
        } finally {
            allowProtectedSeek = false
        }
    }

    private fun ensurePlaying(exo: Any) {
        runCatching {
            exo.javaClass.methods
                .firstOrNull {
                    it.name == "setPlayWhenReady" &&
                        it.parameterCount == 1 &&
                        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                }
                ?.invoke(exo, true)
            exo.javaClass.methods
                .firstOrNull { it.name == "play" && it.parameterCount == 0 }
                ?.invoke(exo)
        }
    }

    private fun shouldSwallowSyntheticBack(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (event.deviceId >= 0) return false
        return isPlaybackActive()
    }

    private fun isPlaybackActive(): Boolean {
        val exo = resolveExoPlayer(null) ?: return false
        val playing = callBoolean(exo, "isPlaying") == true
        val playWhenReady = callBoolean(exo, "getPlayWhenReady") == true
        return playing || playWhenReady
    }

    private fun sanitizeBringToFrontFlags(intent: Intent?): Intent? {
        intent ?: return null
        val dangerous =
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
        intent.flags = (intent.flags and dangerous.inv()) or
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        return intent
    }

    private fun hookNotificationManagerNotify(): Int {
        var count = 0
        val methods = listOf(
            runCatching {
                NotificationManager::class.java.getDeclaredMethod(
                    "notify",
                    Int::class.javaPrimitiveType,
                    Notification::class.java,
                )
            }.getOrNull(),
            runCatching {
                NotificationManager::class.java.getDeclaredMethod(
                    "notify",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Notification::class.java,
                )
            }.getOrNull(),
        )
        for (method in methods) {
            method ?: continue
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val idIndex = if (method.parameterCount == 2) 0 else 1
                    val notifIndex = if (method.parameterCount == 2) 1 else 2
                    val id = chain.args[idIndex] as Int
                    val notification = chain.args[notifIndex] as? Notification
                    val nm = chain.thisObject as? NotificationManager
                    if (notification != null && shouldPatchMediaNotification(id, notification)) {
                        val ctx = resolveNotificationContext(nm)
                        if (ctx != null && patchMediaNotificationInPlace(ctx, notification)) {
                            module.info("Plex：已修补媒体通知 (id=$id, chronometer off + contentIntent)")
                        }
                    }
                    chain.proceed()
                }
                count++
            }
        }
        return count
    }

    private fun shouldPatchMediaNotification(id: Int, notification: Notification): Boolean {
        if (id == MEDIA_NOTIFICATION_REQ) return true
        val template = notification.extras?.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        return template.contains("MediaStyle")
    }

    private fun resolveNotificationContext(nm: NotificationManager?): Context? {
        nm ?: return null
        return runCatching {
            val field = nm.javaClass.declaredFields.firstOrNull {
                Context::class.java.isAssignableFrom(it.type)
            } ?: return null
            field.isAccessible = true
            field.get(nm) as? Context
        }.getOrNull()?.applicationContext
            ?: runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Context
            }.getOrNull()
    }

    private fun patchMediaNotificationInPlace(context: Context, notification: Notification): Boolean {
        var changed = false
        runCatching {
            if (notification.contentIntent == null) {
                notification.contentIntent = PendingIntent.getActivity(
                    context,
                    MEDIA_NOTIFICATION_REQ,
                    buildBringToFrontIntent(packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                changed = true
            }
            val extras = notification.extras
            if (extras != null) {
                if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)) {
                    extras.putBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
                    changed = true
                }
                if (extras.containsKey("android.showChronometer") &&
                    extras.getBoolean("android.showChronometer", false)
                ) {
                    extras.putBoolean("android.showChronometer", false)
                    changed = true
                }
                if (extras.getBoolean(Notification.EXTRA_SHOW_WHEN, false)) {
                    extras.putBoolean(Notification.EXTRA_SHOW_WHEN, false)
                    changed = true
                }
            }
            @Suppress("DEPRECATION")
            if (notification.`when` != 0L) {
                notification.`when` = 0L
                changed = true
            }
        }
        return changed
    }

    private fun installSessionPlayerProgress(classLoader: ClassLoader): Int {
        var count = 0
        val emClass = runCatching {
            Class.forName("tv.plex.video.react.EngineManager", false, classLoader)
        }.getOrNull()
        if (emClass != null) {
            emClass.declaredConstructors.forEach { ctor ->
                runCatching {
                    ctor.isAccessible = true
                    module.hook(ctor).intercept { chain ->
                        val result = chain.proceed()
                        rememberEngineManager(chain.thisObject)
                        mainHandler.post { tryHookSessionPlayerFromEngine(classLoader) }
                        result
                    }
                    count++
                }
            }
        }
        listOf("y7.l", "Df.h", "Y1.e1").forEach { name ->
            val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                ?: return@forEach
            if (hookPlayerAdapterClass(clazz)) {
                sessionPlayerHooked = true
                count++
                module.info("Plex：已 Hook MediaSession Player：$name")
            }
        }
        return count
    }

    private fun installSurfaceRestore(classLoader: ClassLoader): Int {
        var count = 0
        val emClass = runCatching {
            Class.forName("tv.plex.video.react.EngineManager", false, classLoader)
        }.getOrNull()
        if (emClass != null) {
            emClass.declaredConstructors.forEach { ctor ->
                runCatching {
                    ctor.isAccessible = true
                    module.hook(ctor).intercept { chain ->
                        val result = chain.proceed()
                        rememberEngineManager(chain.thisObject)
                        result
                    }
                    count++
                }
            }
            emClass.declaredMethods
                .filter { it.name == "attachView" && it.parameterCount == 1 }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        module.hook(method).intercept { chain ->
                            rememberEngineManager(chain.thisObject)
                            val result = chain.proceed()
                            mainHandler.postDelayed({ restoreSurface("attachView") }, 120)
                            result
                        }
                        count++
                    }
                }
        }
        return count
    }

    private fun rememberEngineManager(manager: Any?) {
        manager ?: return
        engineManagerRef = WeakReference(manager)
        val engine = runCatching {
            manager.javaClass.methods
                .firstOrNull { it.name == "getEngine" && it.parameterCount == 0 }
                ?.invoke(manager)
        }.getOrNull()
        if (engine != null) {
            engineRef = WeakReference(engine)
        }
    }

    private fun tryHookSessionPlayerFromEngine(classLoader: ClassLoader) {
        if (sessionPlayerHooked) return
        val engine = engineRef?.get() ?: return
        val engineClass = engine.javaClass
        val videoEventsClass = runCatching {
            Class.forName("tv.plex.video.react.VideoEvents", false, classLoader)
        }.getOrNull() ?: return

        val pathList = runCatching {
            val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
                .getDeclaredField("pathList")
                .apply { isAccessible = true }
            pathListField.get(classLoader)
        }.getOrNull() ?: return
        val dexElements = runCatching {
            pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
                .get(pathList) as Array<*>
        }.getOrNull() ?: return

        for (element in dexElements) {
            val dexFile = runCatching {
                element!!.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }
                    .get(element)
            }.getOrNull() ?: continue
            @Suppress("DEPRECATION")
            val entries = runCatching {
                dexFile.javaClass.getDeclaredMethod("entries").invoke(dexFile)
                    as? java.util.Enumeration<*>
            }.getOrNull() ?: continue
            while (entries.hasMoreElements()) {
                val name = entries.nextElement()?.toString() ?: continue
                if (!name.startsWith("y7.") && !name.startsWith("of.") && !name.startsWith("z7.")) {
                    continue
                }
                val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                    ?: continue
                val hit = clazz.declaredConstructors.any { ctor ->
                    val types = ctor.parameterTypes
                    types.size >= 3 &&
                        types[0] == engineClass &&
                        types[2] == videoEventsClass
                }
                if (hit && hookPlayerAdapterClass(clazz)) {
                    sessionPlayerHooked = true
                    module.info("Plex：已发现并 Hook MediaSession Player：$name")
                    return
                }
            }
        }
    }

    private fun hookPlayerAdapterClass(clazz: Class<*>): Boolean {
        var hooked = false
        clazz.declaredConstructors
            .filter { it.parameterCount >= 2 }
            .forEach { ctor ->
                runCatching {
                    ctor.isAccessible = true
                    module.hook(ctor).intercept { chain ->
                        val result = chain.proceed()
                        val maybeEngine = chain.args.getOrNull(0)
                        val maybeExo = chain.args.getOrNull(1)
                        if (looksLikeRealExoPlayer(maybeExo)) {
                            exoPlayerRef = WeakReference(maybeExo!!)
                            if (maybeEngine != null) {
                                engineRef = WeakReference(maybeEngine)
                            }
                        }
                        result
                    }
                    hooked = true
                }
            }

        val positionNames = setOf("C1", "c1", "getCurrentPosition", "getContentPosition")
        clazz.declaredMethods
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    (it.name == "getDuration" || it.name in positionNames) &&
                    (it.returnType == Long::class.javaPrimitiveType || it.returnType == Long::class.java)
            }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val exo = resolveExoPlayer(chain.thisObject)
                        val value = when (method.name) {
                            "getDuration" -> firstLong(exo, "getDuration")
                            "c1", "getContentPosition" ->
                                firstLong(exo, "c1", "getContentPosition", "C1", "getCurrentPosition")
                            else ->
                                firstLong(exo, "C1", "getCurrentPosition", "c1", "getContentPosition")
                        }
                        if (value != null && value >= 0L &&
                            (method.name != "getDuration" || value > 0L)
                        ) {
                            if (method.name != "getDuration" && value > 1000L) {
                                savedPositionMs = value
                            }
                            if (!loggedProgressBypass) {
                                loggedProgressBypass = true
                                module.info("Plex：进度直读 ExoPlayer（绕过适配器缓存）")
                            }
                            value
                        } else {
                            chain.proceed()
                        }
                    }
                    hooked = true
                }
            }

        clazz.declaredMethods
            .filter {
                it.name == "isPlaying" &&
                    it.parameterCount == 0 &&
                    (it.returnType == Boolean::class.javaPrimitiveType ||
                        it.returnType == Boolean::class.java)
            }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val exo = resolveExoPlayer(chain.thisObject)
                        callBoolean(exo, "isPlaying") ?: chain.proceed()
                    }
                    hooked = true
                }
            }
        return hooked
    }

    private fun resolveExoPlayer(adapter: Any?): Any? {
        findExoFromAdapter(adapter)?.let { return it }
        findExoPlayer(engineRef?.get())?.let { return it }
        exoPlayerRef?.get()?.let { cached ->
            if (looksLikeRealExoPlayer(cached)) return cached
        }
        return null
    }

    private fun findExoFromAdapter(adapter: Any?): Any? {
        adapter ?: return null
        return adapter.javaClass.declaredFields.firstNotNullOfOrNull { field ->
            runCatching {
                field.isAccessible = true
                val value = field.get(adapter)
                if (looksLikeRealExoPlayer(value)) value else null
            }.getOrNull()
        }
    }

    private fun looksLikeRealExoPlayer(value: Any?): Boolean {
        value ?: return false
        val clsName = value.javaClass.name
        if (clsName.startsWith("y7.") || clsName.startsWith("Y7.")) return false
        val methods = value.javaClass.methods
        val hasDuration = methods.any { it.name == "getDuration" && it.parameterCount == 0 }
        val hasPlay = methods.any { it.name == "play" && it.parameterCount == 0 }
        val hasPosition = methods.any {
            it.parameterCount == 0 &&
                (it.name == "C1" || it.name == "getCurrentPosition" || it.name == "c1")
        }
        return hasDuration && hasPlay && hasPosition
    }

    private fun restoreSurface(reason: String) {
        val manager = engineManagerRef?.get() ?: return
        val engine = runCatching {
            manager.javaClass.methods
                .firstOrNull { it.name == "getEngine" && it.parameterCount == 0 }
                ?.invoke(manager)
        }.getOrNull() ?: engineRef?.get() ?: return
        engineRef = WeakReference(engine)

        val videoView = runCatching {
            manager.javaClass.methods
                .firstOrNull { it.name == "getView" && it.parameterCount == 0 }
                ?.invoke(manager)
        }.getOrNull()
        if (videoView !is View) return
        val surfaceView = runCatching {
            videoView.javaClass.getMethod("getSurfaceView").invoke(videoView) as? SurfaceView
        }.getOrNull() ?: return
        if (surfaceView.visibility != View.VISIBLE) {
            surfaceView.visibility = View.VISIBLE
        }
        val holder = surfaceView.holder
        if (!holder.surface.isValid) return
        val exo = findExoPlayer(engine) ?: return
        if (!bindSurface(exo, holder)) return
        ensurePlaying(exo)
        module.info("Plex：已重绑播放画面（$reason）")
    }

    private fun findExoPlayer(engine: Any?): Any? {
        engine ?: return null
        return engine.javaClass.declaredFields.firstNotNullOfOrNull { field ->
            runCatching {
                field.isAccessible = true
                val value = field.get(engine) ?: return@runCatching null
                if (looksLikeRealExoPlayer(value)) value else null
            }.getOrNull()
        }
    }

    private fun bindSurface(exo: Any, holder: SurfaceHolder): Boolean {
        val direct = runCatching {
            exo.javaClass.getMethod("setVideoSurfaceHolder", SurfaceHolder::class.java)
                .invoke(exo, holder)
            true
        }.getOrDefault(false)
        if (direct) return true
        return runCatching {
            val method = exo.javaClass.methods.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0] == SurfaceHolder::class.java
            } ?: return false
            method.isAccessible = true
            method.invoke(exo, holder)
            true
        }.getOrDefault(false)
    }

    private fun buildBringToFrontIntent(pkg: String): Intent =
        Intent().apply {
            setClassName(pkg, "tv.plex.app.MainActivity")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            putExtra("background_media_guard_bring_to_front", true)
        }

    private fun shouldRewriteSessionIntent(intent: Intent?, requestCode: Int): Boolean {
        if (requestCode == MEDIA_NOTIFICATION_REQ) return true
        intent ?: return false
        val component = intent.component?.className.orEmpty()
        if (component.contains("MainActivity")) return true
        return intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true ||
            intent.action == Intent.ACTION_MAIN
    }

    private fun firstLong(target: Any?, vararg names: String): Long? {
        target ?: return null
        for (name in names) {
            val value = callLong(target, name)
            if (value != null) return value
        }
        return null
    }

    private fun callLong(target: Any?, name: String): Long? {
        target ?: return null
        return runCatching {
            val raw = target.javaClass.methods
                .firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.invoke(target) ?: return null
            when (raw) {
                is Long -> raw
                is Number -> raw.toLong()
                else -> null
            }
        }.getOrNull()
    }

    private fun callBoolean(target: Any?, name: String): Boolean? {
        target ?: return null
        return runCatching {
            target.javaClass.methods
                .firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.invoke(target) as? Boolean
        }.getOrNull()
    }

    companion object {
        private const val MEDIA_NOTIFICATION_REQ = 456
        private const val POSITION_PROTECT_MS = 15_000L
        private const val SEEK_ZERO_THRESHOLD_MS = 1_500L
        private const val NEAR_END_MS = 20_000L
    }
}
