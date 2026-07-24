package io.github.sw1313.backgroundmediaguard.xposed

class HyperOsZeroDataHook(
    private val module: ModuleEntry,
    private val resolver: TargetResolver,
) {
    fun install(classLoader: ClassLoader) {
        val controller = Class.forName(
            "com.miui.powerkeeper.controller.AudioDisguiseController",
            false,
            classLoader,
        )
        val precise = controller.declaredMethods.filter { method ->
            method.name == "handleUidAudioMusicZeroPause" &&
                method.parameterCount >= 1 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        val candidates = precise.ifEmpty {
            module.warn(
                "未找到 HyperOS 零数据暂停入口，回退到 AudioDisguiseController.pauseAudioTracks",
            )
            controller.declaredMethods.filter { method ->
                method.name == "pauseAudioTracks" &&
                    method.parameterCount >= 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
        }
        if (candidates.isEmpty()) error("找不到 HyperOS AudioDisguiseController 暂停入口")

        var controllerHooks = 0
        candidates.forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val uid = chain.args[0] as Int
                if (shouldBlock(uid, "PowerKeeper")) {
                    null
                } else {
                    chain.proceed()
                }
            }
            controllerHooks++
        }
        val audioSystemHooks = installAudioPauseHooks(
            classLoader = classLoader,
            className = "android.media.AudioSystem",
        )
        val audioManagerHooks = installAudioPauseHooks(
            classLoader = classLoader,
            className = "android.media.AudioManager",
        )
        module.info(
            "已安装 HyperOS 零数据暂停保护：PowerKeeper=$controllerHooks，" +
                "AudioSystem=$audioSystemHooks，AudioManager=$audioManagerHooks，" +
                "精确入口=${precise.isNotEmpty()}",
        )
    }

    private fun installAudioPauseHooks(
        classLoader: ClassLoader,
        className: String,
    ): Int {
        val clazz = Class.forName(className, false, classLoader)
        val methods = clazz.declaredMethods.filter { method ->
            method.name == "pauseAudioTracks" &&
                method.parameterCount == 3 &&
                method.parameterTypes.all { it == Int::class.javaPrimitiveType }
        }
        methods.forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val uid = chain.args[0] as Int
                if (shouldBlock(uid, className.substringAfterLast('.'))) {
                    if (method.returnType == Int::class.javaPrimitiveType) 0 else null
                } else {
                    chain.proceed()
                }
            }
        }
        return methods.size
    }

    private fun shouldBlock(uid: Int, source: String): Boolean {
        val enabled = resolver.hyperOsZeroDataEnabled(uid)
        val selected = resolver.isSelectedUid(uid)
        if (enabled && selected) {
            module.info("已阻止 HyperOS zero-data 音轨暂停：uid=$uid，入口=$source")
            return true
        }
        module.info(
            "放行 HyperOS 音轨暂停请求：uid=$uid，入口=$source，enabled=$enabled，selected=$selected",
        )
        return false
    }
}
