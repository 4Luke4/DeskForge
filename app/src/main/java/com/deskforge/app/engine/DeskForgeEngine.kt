package com.deskforge.app.engine

import android.view.Surface
import com.deskforge.app.model.DesktopViewport
import com.deskforge.app.model.RuntimeCapabilities
import com.deskforge.app.model.SessionState

/** Stable Kotlin boundary for the native runtime. */
interface DeskForgeEngine {
    fun inspectCapabilities(): RuntimeCapabilities

    fun startSession(rootfsPath: String, surface: Surface, viewport: DesktopViewport): SessionState

    fun stopSession(): SessionState

    fun attachSurface(surface: Surface, viewport: DesktopViewport): Boolean

    fun detachSurface()

    fun resizeDisplay(viewport: DesktopViewport): Boolean

    fun sendPointer(x: Int, y: Int, buttons: Int): Boolean

    fun sendKey(keysym: Int, pressed: Boolean): Boolean

    fun isDisplayConnected(): Boolean
}
