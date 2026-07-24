package io.github.sw1313.backgroundmediaguard.xposed

class ProcessTrackerHook(
    private val module: ModuleEntry,
    private val resolver: TargetResolver,
) {
    fun install(classLoader: ClassLoader) {
        val processRecord = Class.forName(
            "com.android.server.am.ProcessRecord",
            false,
            classLoader,
        )
        var installed = 0
        processRecord.declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            module.hook(constructor).intercept { chain ->
                val result = chain.proceed()
                resolver.rememberProcess(chain.thisObject ?: result)
                result
            }
            installed++
        }
        module.info("已安装 ProcessRecord 跟踪 Hook：$installed 个构造函数")
    }
}
