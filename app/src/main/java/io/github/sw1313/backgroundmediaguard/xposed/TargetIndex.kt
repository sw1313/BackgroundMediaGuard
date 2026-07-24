package io.github.sw1313.backgroundmediaguard.xposed

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal class TargetIndex(initialTargets: Set<String>) {
    private val targets = AtomicReference(initialTargets.toSet())
    private val packagesByUid = ConcurrentHashMap<Int, Set<String>>()

    fun replaceTargets(values: Set<String>) {
        targets.set(values.toSet())
        packagesByUid.clear()
    }

    fun remember(uid: Int, packageName: String) {
        packagesByUid.compute(uid) { _, old -> old.orEmpty() + packageName }
    }

    fun matchesPackage(packageName: String?): Boolean =
        packageName != null && packageName in targets.get()

    fun matchesUid(uid: Int, loader: (Int) -> Set<String>): Boolean {
        val selected = targets.get()
        if (selected.isEmpty()) return false
        val packages = packagesForUid(uid, loader)
        return packages.any(selected::contains)
    }

    fun packagesForUid(uid: Int, loader: (Int) -> Set<String>): Set<String> =
        packagesByUid.computeIfAbsent(uid, loader)
}
