package com.deskforge.app.engine

import android.content.Context
import com.deskforge.app.BuildConfig
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
            detail = if (prootAvailable) fields[3] else "Verified runtime executable is absent",
        )
    }

    override fun startSession(rootfsPath: String, microphoneEnabled: Boolean): SessionState {
        if (!runtimeIsVerified()) {
            return SessionState.Failed("The verified PRoot runtime is unavailable", recoverable = false)
        }
        if (nativeActiveProcessId() > 0) {
            return SessionState.Failed("A managed Linux session is already running", recoverable = true)
        }

        return try {
            val runtimeDirectory = runtimeStorage.prepare()
            val result = nativeStart(
                prootExecutable.absolutePath,
                prootLoader.absolutePath,
                rootfsPath,
                runtimeDirectory.absolutePath,
                microphoneEnabled,
            )
            if (result > 0) {
                SessionState.Running(result, inspectCapabilities().rendererMode)
            } else {
                runtimeStorage.cleanup()
                SessionState.Failed(nativeLastError(), recoverable = true)
            }
        } catch (_: IllegalStateException) {
            runtimeStorage.cleanup()
            SessionState.Failed("The verified PRoot runtime is unavailable", recoverable = false)
        }
    }

    override fun stopSession(): SessionState = try {
        if (nativeStop()) SessionState.Idle
        else SessionState.Failed(nativeLastError(), recoverable = true)
    } finally {
        runtimeStorage.cleanup()
    }

    /** Restores UI ownership of a native session after an Android configuration change. */
    fun activeSessionState(): SessionState.Running? {
        val processId = nativeActiveProcessId()
        return if (processId > 0 && runtimeIsVerified()) {
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
        microphoneEnabled: Boolean,
    ): Int

    private external fun nativeStop(): Boolean

    private external fun nativeActiveProcessId(): Int

    private external fun nativeLastError(): String

    private companion object {
        init {
            System.loadLibrary("deskforge_engine")
        }
    }
}
