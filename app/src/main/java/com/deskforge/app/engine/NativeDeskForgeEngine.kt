package com.deskforge.app.engine

import android.content.Context
import com.deskforge.app.model.RendererMode
import com.deskforge.app.model.RuntimeCapabilities
import com.deskforge.app.model.SessionState
import java.io.File

/**
 * JNI adapter. Native failures are converted into structured application state so the UI never
 * reports a session as running unless the supervised process was actually created.
 */
class NativeDeskForgeEngine(context: Context) : DeskForgeEngine {
    private val applicationContext = context.applicationContext
    private val prootExecutable = File(applicationContext.applicationInfo.nativeLibraryDir, "libproot.so")

    override fun inspectCapabilities(): RuntimeCapabilities {
        val fields = nativeInspect(prootExecutable.absolutePath).split('|', limit = 4)
        if (fields.size != 4) {
            return RuntimeCapabilities(
                prootAvailable = false,
                vulkanAvailable = false,
                audioAvailable = false,
                rendererMode = RendererMode.Software("Invalid native capability response"),
                detail = "The native engine returned malformed diagnostics.",
            )
        }

        val prootAvailable = fields[0].toBooleanStrictOrNull() ?: false
        val vulkanAvailable = fields[1].toBooleanStrictOrNull() ?: false
        val audioAvailable = fields[2].toBooleanStrictOrNull() ?: false
        val rendererMode = if (vulkanAvailable) {
            RendererMode.Accelerated("Vulkan")
        } else {
            RendererMode.Software("Vulkan capability probe failed")
        }
        return RuntimeCapabilities(
            prootAvailable = prootAvailable,
            vulkanAvailable = vulkanAvailable,
            audioAvailable = audioAvailable,
            rendererMode = rendererMode,
            detail = fields[3],
        )
    }

    override fun startSession(rootfsPath: String, microphoneEnabled: Boolean): SessionState {
        val result = nativeStart(prootExecutable.absolutePath, rootfsPath, microphoneEnabled)
        return if (result > 0) {
            SessionState.Running(result, inspectCapabilities().rendererMode)
        } else {
            SessionState.Failed(nativeLastError(), recoverable = true)
        }
    }

    override fun stopSession(): SessionState =
        if (nativeStop()) SessionState.Idle
        else SessionState.Failed(nativeLastError(), recoverable = true)

    /** Restores UI ownership of a native session after an Android configuration change. */
    fun activeSessionState(): SessionState.Running? {
        val processId = nativeActiveProcessId()
        return if (processId > 0) SessionState.Running(processId, inspectCapabilities().rendererMode) else null
    }

    private external fun nativeInspect(prootPath: String): String

    private external fun nativeStart(prootPath: String, rootfsPath: String, microphoneEnabled: Boolean): Int

    private external fun nativeStop(): Boolean

    private external fun nativeActiveProcessId(): Int

    private external fun nativeLastError(): String

    private companion object {
        init {
            System.loadLibrary("deskforge_engine")
        }
    }
}
