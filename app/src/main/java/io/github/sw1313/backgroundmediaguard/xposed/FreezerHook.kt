package io.github.sw1313.backgroundmediaguard.xposed

class FreezerHook(
    private val module: ModuleEntry,
    private val resolver: TargetResolver,
    private val registry: MediaProtectionRegistry,
) {
    fun install(classLoader: ClassLoader) {
        val optimizer = Class.forName(
            "com.android.server.am.CachedAppOptimizer",
            false,
            classLoader,
        )
        val candidates = optimizer.declaredMethods.filter { method ->
            method.name == "freezeAppAsyncInternalLSP" &&
                method.parameterCount >= 1 &&
                method.parameterTypes[0].name == "com.android.server.am.ProcessRecord"
        }
        candidates.forEach { method ->
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val process = chain.args[0]
                resolver.rememberProcess(process)
                if (resolver.freezerEnabled(process) &&
                    resolver.shouldProtectProcess(process, registry)
                ) {
                    null
                } else {
                    chain.proceed()
                }
            }
        }
        module.info("已安装缓存冻结保护 Hook：${candidates.size} 个方法")
    }
}
