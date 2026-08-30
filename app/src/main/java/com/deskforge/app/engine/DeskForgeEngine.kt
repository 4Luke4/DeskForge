package com.deskforge.app.engine

import com.deskforge.app.model.RuntimeCapabilities
import com.deskforge.app.model.SessionState

/** Stable Kotlin boundary for the native runtime. */
interface DeskForgeEngine {
    fun inspectCapabilities(): RuntimeCapabilities

    fun startSession(rootfsPath: String, microphoneEnabled: Boolean): SessionState

    fun stopSession(): SessionState
}
