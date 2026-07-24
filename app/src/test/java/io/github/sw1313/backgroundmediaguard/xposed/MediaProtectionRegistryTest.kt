package io.github.sw1313.backgroundmediaguard.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProtectionRegistryTest {
    private var now = 1_000L
    private val registry = MediaProtectionRegistry { now }

    @Test
    fun activeSessionProtectsPackageAndUid() {
        registry.update("session", "org.jellyfin.mobile", 10522, active = true)

        assertTrue(registry.isProtected(setOf("org.jellyfin.mobile"), null, 120_000))
        assertTrue(registry.isProtected(emptySet(), 10522, 120_000))
    }

    @Test
    fun inactiveSessionExpiresAfterGracePeriod() {
        registry.update("session", "com.android.chrome", 10347, active = true)
        now += 10_000
        registry.update("session", "com.android.chrome", 10347, active = false)

        now += 109_999
        assertTrue(registry.isProtected(setOf("com.android.chrome"), null, 120_000))

        now += 10_002
        assertFalse(registry.isProtected(setOf("com.android.chrome"), null, 120_000))
        assertEquals(0, registry.sessionCount())
    }

    @Test
    fun anotherActiveSessionKeepsPackageProtected() {
        registry.update("first", "com.android.chrome", 10347, active = false)
        registry.update("second", "com.android.chrome", 10347, active = true)
        now += 500_000

        assertTrue(registry.isProtected(setOf("com.android.chrome"), null, 120_000))
    }

    @Test
    fun removingSessionStopsProtection() {
        registry.update("session", "org.jellyfin.mobile", 10522, active = true)
        registry.remove("session")

        assertFalse(registry.isProtected(setOf("org.jellyfin.mobile"), null, 120_000))
    }

    @Test
    fun oomAdjIsClampedOnlyForProtectedProcess() {
        assertEquals(200, OomAdjHook.clampAdj(905, protected = true))
        assertEquals(100, OomAdjHook.clampAdj(100, protected = true))
        assertEquals(905, OomAdjHook.clampAdj(905, protected = false))
    }
}
