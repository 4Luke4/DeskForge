package com.deskforge.app.engine

import android.content.Context
import android.view.Surface
import com.deskforge.app.BuildConfig
import com.deskforge.app.model.DesktopViewport
import com.deskforge.app.model.RendererMode
import com.deskforge.app.model.RuntimeCapabilities
import com.deskforge.app.model.SessionFailure
import com.deskforge.app.model.SessionState
import java.io.File

/**
 * JNI adapter. Native failures are converted into structured application state so the UI never
 * reports a session as running unless the supervised process was actually created.
 */
class NativeDeskForgeEngine(context: Context) : DeskForgeEngine {
    private val applicationContext = context.applicationContext
    private val prootExecutable = File(applicationContext.applicationInfo.nativeLibraryDir, "libproot.so")
    private val prootLoader = File(applicationContext.applicationInfo.nativeLibraryDir, "libproot-loader.so")
    private val runtimeStorage = ProotRuntimeStorage(File(applicationContext.codeCacheDir, "proot"))

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

        val nativeProotAvailable = fields[0].toBooleanStrictOrNull() ?: false
        val prootAvailable = nativeProotAvailable && runtimeIsVerified()
        val vulkanAvailable = fields[1].toBooleanStrictOrNull() ?: false
        val audioAvailable = fields[2].toBooleanStrictOrNull() ?: false
        // Vulkan-loader presence is diagnostic only until an accelerated desktop path is qualified.
        val rendererMode = RendererMode.Software("RFB framebuffer")
        return RuntimeCapabilities(
            prootAvailable = prootAvailable,
            vulkanAvailable = vulkanAvailable,
            audioAvailable = audioAvailable,
            rendererMode = rendererMode,
            detail = if (prootAvailable) fields[3] else "Verified runtime executable is absent",
        )
    }

    override fun startSession(
        rootfsPath: String,
        surface: Surface,
        viewport: DesktopViewport,
    ): SessionState {
        if (!runtimeIsVerified()) {
            return SessionState.Failed(SessionFailure.RUNTIME_UNAVAILABLE, recoverable = false)
        }
        if (nativeActiveProcessId() > 0) {
            return SessionState.Failed(SessionFailure.SESSION_ALREADY_RUNNING, recoverable = true)
        }

        return try {
            val runtimeDirectory = runtimeStorage.prepare()
            val result = nativeStart(
                prootExecutable.absolutePath,
                prootLoader.absolutePath,
                rootfsPath,
                runtimeDirectory.absolutePath,
                surface,
                viewport.widthPx,
                viewport.heightPx,
                viewport.densityDpi,
            )
            if (result > 0) {
                SessionState.Running(result, inspectCapabilities().rendererMode)
            } else {
                runtimeStorage.cleanup()
                SessionState.Failed(SessionFailure.SESSION_START_FAILED, recoverable = true)
            }
        } catch (_: IllegalStateException) {
            runtimeStorage.cleanup()
            SessionState.Failed(SessionFailure.RUNTIME_UNAVAILABLE, recoverable = false)
        }
    }

    override fun stopSession(): SessionState = try {
        if (nativeStop()) SessionState.Idle
        else SessionState.Failed(SessionFailure.SESSION_STOP_FAILED, recoverable = true)
    } finally {
        runtimeStorage.cleanup()
    }

    override fun attachSurface(surface: Surface, viewport: DesktopViewport): Boolean =
        nativeAttachSurface(surface, viewport.widthPx, viewport.heightPx)

    override fun detachSurface() = nativeDetachSurface()

    override fun resizeDisplay(viewport: DesktopViewport): Boolean =
        nativeResizeDisplay(viewport.widthPx, viewport.heightPx)

    override fun sendPointer(x: Int, y: Int, buttons: Int): Boolean =
        nativeSendPointer(x, y, buttons)

    override fun sendKey(keysym: Int, pressed: Boolean): Boolean = nativeSendKey(keysym, pressed)

    override fun isDisplayConnected(): Boolean = nativeDisplayConnected()

    /** Restores UI ownership of a native session after an Android configuration change. */
    fun activeSessionState(): SessionState.Running? {
        val processId = nativeActiveProcessId()
        return if (processId > 0 && runtimeIsVerified() && nativeDisplayConnected()) {
            SessionState.Running(processId, inspectCapabilities().rendererMode)
        } else {
            null
        }
    }

    private external fun nativeInspect(prootPath: String): String

    private fun runtimeIsVerified(): Boolean {
        val executableStatus = ProotRuntimeIntegrity.verify(
            executable = prootExecutable,
            expectedSha256 = BuildConfig.PROOT_SHA256,
            expectedSizeBytes = BuildConfig.PROOT_SIZE_BYTES,
        )
        val loaderStatus = ProotRuntimeIntegrity.verify(
            executable = prootLoader,
            expectedSha256 = BuildConfig.PROOT_LOADER_SHA256,
            expectedSizeBytes = BuildConfig.PROOT_LOADER_SIZE_BYTES,
        )
        return executableStatus is ProotRuntimeStatus.Verified &&
            loaderStatus is ProotRuntimeStatus.Verified
    }

    private external fun nativeStart(
        prootPath: String,
        prootLoaderPath: String,
        rootfsPath: String,
        runtimeDirectoryPath: String,
        surface: Surface,
        viewportWidth: Int,
        viewportHeight: Int,
        densityDpi: Int,
    ): Int

    private external fun nativeStop(): Boolean

    private external fun nativeActiveProcessId(): Int

    private external fun nativeLastError(): String

    private external fun nativeAttachSurface(surface: Surface, width: Int, height: Int): Boolean

    private external fun nativeDetachSurface()

    private external fun nativeResizeDisplay(width: Int, height: Int): Boolean

    private external fun nativeSendPointer(x: Int, y: Int, buttons: Int): Boolean

    private external fun nativeSendKey(keysym: Int, pressed: Boolean): Boolean

    private external fun nativeDisplayConnected(): Boolean

    private companion object {
        init {
            System.loadLibrary("deskforge_engine")
        }
    }
}
