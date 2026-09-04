package com.deskforge.app.display

import com.deskforge.app.BuildConfig

/** Qualifies broker-owned Android graphics capabilities outside the isolated service UID. */
object DirectDisplayCapabilityProbe {
    private val nativeAvailable = runCatching { System.loadLibrary("deskforge_engine") }.isSuccess

    fun probe(): String {
        if (!BuildConfig.EXPERIMENTAL_DIRECT_DISPLAY) {
            return "unavailable:Direct-display capability probe is disabled"
        }
        if (!nativeAvailable) {
            return "unavailable:Direct-display native runtime unavailable"
        }
        // The isolated_app SELinux domain cannot reach the platform graphics allocator.
        return runCatching { nativeProbe() }
            .getOrDefault("unavailable:Direct-display capability probe failed")
    }

    private external fun nativeProbe(): String
}
