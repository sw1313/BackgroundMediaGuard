package io.github.sw1313.backgroundmediaguard.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetIndexTest {
    @Test
    fun matchesAnyPackageOfSharedUid() {
        val index = TargetIndex(setOf("org.jellyfin.mobile"))

        assertTrue(index.matchesUid(10522) {
            setOf("org.jellyfin.mobile", "org.example.shared")
        })
    }

    @Test
    fun remembersOwnerForIsolatedUid() {
        val index = TargetIndex(setOf("com.android.chrome"))
        index.remember(99001, "com.android.chrome")

        assertTrue(index.matchesUid(99001) { emptySet() })
    }

    @Test
    fun replacingTargetsInvalidatesUidCache() {
        val index = TargetIndex(setOf("com.android.chrome"))
        index.remember(99001, "com.android.chrome")
        index.replaceTargets(setOf("org.jellyfin.mobile"))

        assertFalse(index.matchesUid(99001) { emptySet() })
    }

    @Test
    fun emptySelectionNeverMatches() {
        val index = TargetIndex(emptySet())

        assertFalse(index.matchesUid(10522) { setOf("org.jellyfin.mobile") })
        assertFalse(index.matchesPackage("org.jellyfin.mobile"))
    }
}
