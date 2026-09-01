package com.deskforge.app.engine

import android.view.Surface
import com.deskforge.app.model.DesktopViewport
import com.deskforge.app.model.AudioTransportSnapshot
import com.deskforge.app.model.ClipboardTransportSnapshot
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

    fun sendText(text: String): Boolean

    fun clipboardSnapshot(): ClipboardTransportSnapshot

    fun offerClipboardText(text: String): Boolean

    fun requestClipboardText(): Boolean

    fun takeClipboardText(): String?

    fun audioSnapshot(): AudioTransportSnapshot

    fun setPlaybackAudible(enabled: Boolean): Boolean

    fun setMicrophoneCaptureEnabled(enabled: Boolean): Boolean

    fun isDisplayConnected(): Boolean
}
