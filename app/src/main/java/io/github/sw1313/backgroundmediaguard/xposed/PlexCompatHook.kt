package io.github.sw1313.backgroundmediaguard.xposed

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

/**
 * Plex-specific fixes (com.plexapp.android). Split into independent toggles:
 * - PiP dismiss keep playing
 * - Media notification (timeline + bring-to-front)
 * - Surface restore after background auto-next
 * - Prevent restart-from-beginning (soft Intent + swallow synthetic BACK)
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

    /** Live ExoPlayer (T0.G) held by MediaSession adapter ctor (engine, exo, VideoEvents). */
    @Volatile
    private var exoPlayerRef: WeakReference<Any>? = null

    @Volatile
    private var sessionPlayerHooked = false

    @Volatile
    private var loggedProgressBypass = false

    /** 最近一次可信播放进度；后台恢复时用。 */
    @Volatile
    private var savedPositionMs = 0L

    fun install(classLoader: ClassLoader) {
        var installed = 0
        if (pipKeepPlaying) {
            installed += installPipKeepPlaying(classLoader)
        }
        if (mediaNotificationFix) {
            installed += installNotificationBringToFront(classLoader)
            installed += installSessionPlayerProgress(classLoader)
        } else if (preventRestart) {
            // 无通知修复时仍需软化拉起 Intent，避免点控件/系统回前台 CLEAR_TOP 重挂载。
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

        // 实测：Plex MediaStyle 通知 contentIntent=null 且 showChronometer=true → 显示 0:00、无法点回前台。
        count += hookNotificationManagerNotify()
        count += installBringToFrontIntentRewrite(classLoader)

        // 补 session metadata 时长，否则系统媒体面板可能没有可拖动进度条。
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
                    if (context?.packageName == packageName && shouldRewriteSessionIntent(original, requestCode)) {
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
            val pmClass = Class.forName("android.app.ApplicationPackageManager", false, classLoader)
            val method = pmClass.getDeclaredMethod("getLaunchIntentForPackage", String::class.java)
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val pkg = chain.args[0] as? String
                if (pkg == packageName) {
                    // 只软化 flags，保留系统原始 MAIN/LAUNCHER，避免全局换成自定义 Intent 引发副作用。
                    val original = chain.proceed() as? Intent
                    sanitizeBringToFrontFlags(original) ?: buildBringToFrontIntent(packageName)
                } else {
                    chain.proceed()
                }
            }
            count++
        }.onFailure {
            module.warn("Plex getLaunchIntentForPackage Hook 失败: ${it.message}")
        }
        return count
    }

    /**
     * 防止「从头开始播放」/后台卡死：
     * - 软化拉起 Intent（见 [buildBringToFrontIntent] / launchIntent flags）
     * - 忽略系统注入 BACK
     * - 回后台时主动清空 Video Surface，避免 MediaCodecVideoRenderer ERROR 导致控件无法续播
     * - 点播放时若播放器已 ERROR，尝试恢复
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
                            module.info(
                                "Plex：忽略系统注入 BACK (action=${event.action}, deviceId=${event.deviceId})",
                            )
                            true
                        } else {
                            chain.proceed()
                        }
                    }
                    count++
                }.onFailure {
                    module.warn("Plex dispatchKeyEvent Hook 失败: ${it.message}")
                }
            }

        // 仅在真正进后台时卸 Surface。不要在 onPause 动手：HyperOS 回桌常先 onPause 再进 PiP，
        // 此时 isInPictureInPictureMode 仍为 false，过早卸 Surface 会把 PiP/解码打成 ERROR。
        runCatching {
            val stop = Activity::class.java.getDeclaredMethod("onStop")
            stop.isAccessible = true
            module.hook(stop).intercept { chain ->
                val activity = chain.thisObject as? Activity
                if (activity != null &&
                    activity.javaClass.name == "tv.plex.app.MainActivity" &&
                    !activity.isInPictureInPictureMode
                ) {
                    detachSurfaceForBackground("onStop")
                }
                chain.proceed()
            }
            count++
        }.onFailure {
            module.warn("Plex onStop surface Hook 失败: ${it.message}")
        }

        count += installPlayRecovery(classLoader)
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

    private fun installPlayRecovery(classLoader: ClassLoader): Int {
        var count = 0
        val eventsClass = runCatching {
            Class.forName("tv.plex.video.react.VideoEvents", false, classLoader)
        }.getOrNull() ?: return 0
        eventsClass.declaredMethods
            .filter { it.name == "sendPlayCommand" && it.parameterCount == 0 }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        mainHandler.post { recoverPlaybackIfNeeded("play-command") }
                        result
                    }
                    count++
                }.onFailure {
                    module.warn("Plex sendPlayCommand recovery Hook 失败: ${it.message}")
                }
            }
        return count
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

    private fun rememberPosition(reason: String) {
        val live = firstLong(resolveExoPlayer(null), "C1", "getCurrentPosition", "c1", "getContentPosition")
        if (live != null && live > 1000L) {
            savedPositionMs = live
            module.info("Plex：记住进度 ${savedPositionMs}ms（$reason）")
        }
    }

    private fun detachSurfaceForBackground(reason: String) {
        val exo = resolveExoPlayer(null) ?: return
        if (!isPlaybackActive()) return
        rememberPosition(reason)
        if (!clearVideoSurface(exo)) return
        // 保持「想播放」，只去掉失效画面，音频继续。
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
        module.info("Plex：后台已卸 Surface，保持音频（$reason）")
    }

    private fun clearVideoSurface(exo: Any): Boolean {
        val byHolder = runCatching {
            val method = exo.javaClass.methods.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0] == SurfaceHolder::class.java
            } ?: return@runCatching false
            method.isAccessible = true
            method.invoke(exo, null)
            true
        }.getOrDefault(false)
        if (byHolder) return true
        return runCatching {
            val method = exo.javaClass.methods.firstOrNull {
                it.name.contains("VideoSurface", ignoreCase = true) &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == android.view.Surface::class.java
            } ?: return false
            method.isAccessible = true
            method.invoke(exo, null)
            true
        }.getOrDefault(false)
    }

    private fun recoverPlaybackIfNeeded(reason: String) {
        val exo = resolveExoPlayer(null) ?: return
        val error = runCatching {
            exo.javaClass.methods
                .firstOrNull { it.name == "getPlayerError" && it.parameterCount == 0 }
                ?.invoke(exo)
        }.getOrNull()
        val playing = callBoolean(exo, "isPlaying") == true
        if (error == null && playing) return

        val pos = savedPositionMs.takeIf { it > 1000L }
            ?: firstLong(exo, "C1", "getCurrentPosition", "c1", "getContentPosition")
            ?: 0L
        module.info(
            "Plex：恢复播放（$reason, error=${error != null}, pos=$pos）",
        )
        // 后台恢复时不要绑失效 Surface。
        clearVideoSurface(exo)
        runCatching {
            if (pos > 0L) {
                exo.javaClass.methods
                    .firstOrNull {
                        it.name == "seekTo" &&
                            it.parameterCount == 1 &&
                            it.parameterTypes[0] == Long::class.javaPrimitiveType
                    }
                    ?.invoke(exo, pos)
            }
            exo.javaClass.methods
                .firstOrNull { it.name == "prepare" && it.parameterCount == 0 }
                ?.invoke(exo)
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
        }.onFailure {
            module.warn("Plex：恢复播放失败: ${it.message}")
        }
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
            }.onFailure {
                module.warn("Plex NotificationManager.notify Hook 失败: ${it.message}")
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

    /**
     * 原地改 Notification，避免 recoverBuilder 弄丢 MediaStyle / session token。
     */
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
                // 关掉 when/计时展示，避免系统把它画成 0:00 计时器。
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
        }.onFailure {
            module.warn("Plex 通知原地修补失败: ${it.message}")
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

        // Media3 Player 适配器：进度在 C1/c1，时长在 getDuration；缓存字段后台常不刷新。
        listOf("y7.l", "Df.h", "Y1.e1").forEach { name ->
            val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: return@forEach
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

        val activityClass = runCatching {
            Class.forName("tv.plex.app.MainActivity", false, classLoader)
        }.getOrNull()
        if (activityClass != null) {
            listOf("onResume", "onStart").forEach { name ->
                activityClass.declaredMethods
                    .filter { it.name == name && it.parameterCount == 0 }
                    .forEach { method ->
                        runCatching {
                            method.isAccessible = true
                            module.hook(method).intercept { chain ->
                                val result = chain.proceed()
                                mainHandler.postDelayed({ restoreSurface(name) }, 160)
                                result
                            }
                            count++
                        }
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

        // Discover adapter: constructor(engine, exoPlayer-like, VideoEvents)
        val loader = classLoader
        val pathList = runCatching {
            val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
                .getDeclaredField("pathList")
                .apply { isAccessible = true }
            pathListField.get(loader)
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
                dexFile.javaClass.getDeclaredMethod("entries").invoke(dexFile) as? java.util.Enumeration<*>
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

        // Remember exo from adapter ctor(engine, exoPlayer, VideoEvents).
        clazz.declaredConstructors
            .filter { it.parameterCount >= 2 }
            .forEach { ctor ->
                runCatching {
                    ctor.isAccessible = true
                    module.hook(ctor).intercept { chain ->
                        val result = chain.proceed()
                        val maybeEngine = chain.args.getOrNull(0)
                        val maybeExo = chain.args.getOrNull(1)
                        if (looksLikeExoPlayer(maybeExo)) {
                            exoPlayerRef = WeakReference(maybeExo!!)
                            if (maybeEngine != null) {
                                engineRef = WeakReference(maybeEngine)
                            }
                            module.info("Plex：已缓存适配器内 ExoPlayer (${clazz.name})")
                        }
                        result
                    }
                    hooked = true
                }
            }

        // Media3 混淆后：当前位置 C1 / 内容位置 c1，不是 getCurrentPosition。
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
                            "getDuration" ->
                                firstLong(exo, "getDuration")
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
                                module.info("Plex：进度/时长改为直读 ExoPlayer（${method.name}=$value）")
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
                !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    (it.name == "isPlaying" || it.name == "i1" || it.name == "C0") &&
                    (it.returnType == Boolean::class.javaPrimitiveType ||
                        it.returnType == java.lang.Boolean::class.java)
            }
            .forEach { method ->
                runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val exo = resolveExoPlayer(chain.thisObject)
                        // 只读 ExoPlayer.isPlaying；勿把适配器混淆名 C0/i1 套到 exo 上。
                        val playing = callBoolean(exo, "isPlaying")
                        if (playing != null) playing else chain.proceed()
                    }
                    hooked = true
                }
            }
        return hooked
    }

    private fun resolveExoPlayer(adapter: Any?): Any? {
        findExoFromAdapter(adapter)?.let { return it }
        exoPlayerRef?.get()?.let { return it }
        return findExoPlayer(engineRef?.get())
    }

    private fun findExoFromAdapter(adapter: Any?): Any? {
        adapter ?: return null
        return adapter.javaClass.declaredFields.firstNotNullOfOrNull { field ->
            runCatching {
                field.isAccessible = true
                val value = field.get(adapter)
                if (looksLikeExoPlayer(value)) value else null
            }.getOrNull()
        }
    }

    private fun looksLikeExoPlayer(value: Any?): Boolean {
        value ?: return false
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
        // Resume rendering if player still wants to play.
        runCatching {
            val playWhenReady = callBoolean(exo, "getPlayWhenReady") == true
            val playing = callBoolean(exo, "isPlaying") == true
            if (playWhenReady || playing) {
                exo.javaClass.methods
                    .firstOrNull { it.name == "play" && it.parameterCount == 0 }
                    ?.invoke(exo)
            }
        }
        module.info("Plex：已重绑播放画面（$reason）")
    }

    private fun findExoPlayer(engine: Any?): Any? {
        engine ?: return null
        return engine.javaClass.declaredFields.firstNotNullOfOrNull { field ->
            runCatching {
                field.isAccessible = true
                val value = field.get(engine) ?: return@runCatching null
                val hasDuration = value.javaClass.methods.any {
                    it.name == "getDuration" && it.parameterCount == 0
                }
                val hasPlay = value.javaClass.methods.any {
                    it.name == "play" && it.parameterCount == 0
                }
                if (hasDuration && hasPlay) value else null
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

    private fun buildBringToFrontIntent(pkg: String): Intent {
        // 不用 ACTION_MAIN + CATEGORY_LAUNCHER，降低被当成「重新启动任务」的概率。
        return Intent().apply {
            setClassName(pkg, "tv.plex.app.MainActivity")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            putExtra("background_media_guard_bring_to_front", true)
        }
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

    private fun firstBoolean(target: Any?, vararg names: String): Boolean? {
        target ?: return null
        for (name in names) {
            val value = callBoolean(target, name)
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
    }
}
