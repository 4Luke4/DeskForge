package com.deskforge.app.engine

import android.content.Context
import android.os.SystemClock
import android.os.FileObserver
import android.view.Surface
import com.deskforge.app.BuildConfig
import com.deskforge.app.graphics.GraphicsTransportController
import com.deskforge.app.model.AudioFailure
import com.deskforge.app.model.AudioMicrophoneStatus
import com.deskforge.app.model.AudioPlaybackStatus
import com.deskforge.app.model.AudioTransportSnapshot
import com.deskforge.app.model.ClipboardFailure
import com.deskforge.app.model.ClipboardTransportSnapshot
import com.deskforge.app.model.ClipboardTransportStatus
import com.deskforge.app.model.DesktopViewport
import com.deskforge.app.model.GraphicsFallbackReason
import com.deskforge.app.model.GraphicsTransportSnapshot
import com.deskforge.app.model.GraphicsTransportStatus
import com.deskforge.app.model.RendererMode
import com.deskforge.app.model.RuntimeCapabilities
import com.deskforge.app.model.SessionFailure
import com.deskforge.app.model.SessionState
import com.deskforge.app.model.RendererPreference
import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JNI adapter. Native failures are converted into structured application state so the UI never
 * reports a session as running unless the supervised process was actually created.
 */
class NativeDeskForgeEngine(context: Context) : DeskForgeEngine {
    private val applicationContext = context.applicationContext
    private val prootExecutable = File(applicationContext.applicationInfo.nativeLibraryDir, "libproot.so")
    private val prootLoader = File(applicationContext.applicationInfo.nativeLibraryDir, "libproot-loader.so")
    private val runtimeStorage = ProotRuntimeStorage(File(applicationContext.codeCacheDir, "proot"))
    private val graphics = GraphicsTransportController(applicationContext, ::nativeCreateGraphicsListener)

    override fun inspectCapabilities(): RuntimeCapabilities {
        val fields = nativeInspect(prootExecutable.absolutePath).split('|', limit = 3)
        if (fields.size != 3) {
            return RuntimeCapabilities(
                prootAvailable = false,
                guestGraphicsAvailable = false,
                audioAvailable = false,
                rendererMode = RendererMode.Software(
                    reason = GraphicsFallbackReason.RUNTIME_UNAVAILABLE,
                    detail = "Invalid native capability response",
                ),
                detail = "The native engine returned malformed diagnostics.",
            )
        }

        val nativeProotAvailable = fields[0].toBooleanStrictOrNull() ?: false
        val prootAvailable = nativeProotAvailable && runtimeIsVerified()
        val audioAvailable = fields[1].toBooleanStrictOrNull() ?: false
        val rendererSnapshot = graphics.snapshot()
        return RuntimeCapabilities(
            prootAvailable = prootAvailable,
            guestGraphicsAvailable = rendererSnapshot.status == GraphicsTransportStatus.READY,
            audioAvailable = audioAvailable,
            rendererMode = rendererSnapshot.rendererMode,
            detail = if (prootAvailable) fields[2] else "Verified runtime executable is absent",
        )
    }

    override fun graphicsSnapshot(): GraphicsTransportSnapshot = graphics.snapshot()

    override fun startSession(
        rootfsPath: String,
        surface: Surface,
        viewport: DesktopViewport,
        rendererPreference: RendererPreference,
    ): SessionState {
        if (!runtimeIsVerified()) {
            return SessionState.Failed(SessionFailure.RUNTIME_UNAVAILABLE, recoverable = false)
        }
        if (nativeActiveProcessId() > 0) {
            return SessionState.Failed(SessionFailure.SESSION_ALREADY_RUNNING, recoverable = true)
        }

        return try {
            val runtimeDirectory = runtimeStorage.prepare()
            val hostRendererMode = graphics.start(
                runtimeDirectory,
                BuildConfig.GRAPHICS_STARTUP_TIMEOUT_MS,
                rendererPreference,
                viewport.refreshRateHz,
            )
            if (graphics.snapshot().status == GraphicsTransportStatus.FAILED) {
                runtimeStorage.cleanup()
                return SessionState.Failed(SessionFailure.RENDERER_UNAVAILABLE, recoverable = true)
            }
            if (hostRendererMode is RendererMode.Software) {
                // A closed socket pathname must not make the guest spend its probe budget on a dead transport.
                File(runtimeDirectory, "virgl.sock").delete()
            }
            val result = nativeStart(
                prootExecutable.absolutePath,
                prootLoader.absolutePath,
                rootfsPath,
                runtimeDirectory.absolutePath,
                surface,
                viewport.widthPx,
                viewport.heightPx,
                viewport.densityDpi,
                viewport.refreshRateHz,
                rendererPreference.name.lowercase(),
            )
            if (result > 0) {
                val guestMode = awaitGuestGraphicsMode(runtimeDirectory)
                val rendererMode = when {
                    hostRendererMode is RendererMode.Software && guestMode == "software" -> hostRendererMode
                    hostRendererMode is RendererMode.Accelerated && guestMode in setOf("venus", "virgl") ->
                        graphics.guestSelected(guestMode.orEmpty(), hostRendererMode.hostRenderer)
                    else -> graphics.guestProbeFailed("Fedora renderer qualification failed")
                }
                if (rendererPreference != RendererPreference.AUTO &&
                    rendererMode is RendererMode.Software &&
                    rendererPreference != RendererPreference.LLVMPIPE
                ) {
                    nativeStop()
                    graphics.stop()
                    runtimeStorage.cleanup()
                    return SessionState.Failed(SessionFailure.RENDERER_UNAVAILABLE, recoverable = true)
                }
                SessionState.Running(result, rendererMode)
            } else {
                graphics.stop()
                runtimeStorage.cleanup()
                SessionState.Failed(SessionFailure.SESSION_START_FAILED, recoverable = true)
            }
        } catch (_: IllegalStateException) {
            graphics.stop()
            runtimeStorage.cleanup()
            SessionState.Failed(SessionFailure.RUNTIME_UNAVAILABLE, recoverable = false)
        }
    }

    override fun stopSession(): SessionState = try {
        if (nativeStop()) SessionState.Idle
        else SessionState.Failed(SessionFailure.SESSION_STOP_FAILED, recoverable = true)
    } finally {
        graphics.stop()
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

    override fun sendText(text: String): Boolean {
        val keysyms = RfbTextEncoder.encode(text) ?: return false
        return nativeSendText(keysyms)
    }

    override fun clipboardSnapshot(): ClipboardTransportSnapshot {
        val values = nativeClipboardSnapshot()
        val status = values.getOrNull(0)?.let { ClipboardTransportStatus.entries.getOrNull(it) }
            ?: ClipboardTransportStatus.UNSUPPORTED
        val failure = when (values.getOrNull(2) ?: 0) {
            1 -> ClipboardFailure.TEXT_TOO_LARGE
            2 -> ClipboardFailure.INVALID_TEXT
            3 -> ClipboardFailure.TRANSFER_TIMEOUT
            4 -> ClipboardFailure.TRANSFER_FAILED
            else -> null
        }
        return ClipboardTransportSnapshot(
            status = status,
            remoteTextAvailable = values.getOrNull(1) == 1,
            failure = failure,
        )
    }

    override fun offerClipboardText(text: String): Boolean {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_CLIPBOARD_TEXT_BYTES) return false
        return nativeOfferClipboardText(bytes)
    }

    override fun requestClipboardText(): Boolean = nativeRequestClipboardText()

    override fun takeClipboardText(): String? {
        val bytes = nativeTakeClipboardText() ?: return null
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }

    override fun audioSnapshot(): AudioTransportSnapshot {
        val values = nativeAudioSnapshot()
        val failure = when (values.getOrNull(2)?.toInt() ?: 0) {
            1 -> AudioFailure.TRANSPORT_UNAVAILABLE
            2 -> AudioFailure.PLAYBACK_OPEN_FAILED
            3 -> AudioFailure.PLAYBACK_DISCONNECTED
            4 -> AudioFailure.MICROPHONE_OPEN_FAILED
            5 -> AudioFailure.MICROPHONE_DISCONNECTED
            else -> null
        }
        return AudioTransportSnapshot(
            playbackStatus = values.getOrNull(0)?.toInt()?.let { AudioPlaybackStatus.entries.getOrNull(it) }
                ?: AudioPlaybackStatus.UNAVAILABLE,
            microphoneStatus = values.getOrNull(1)?.toInt()?.let { AudioMicrophoneStatus.entries.getOrNull(it) }
                ?: AudioMicrophoneStatus.OFF,
            failure = failure,
            outputDeviceId = values.getOrNull(3)?.toInt()?.takeUnless { it == 0 },
            inputDeviceId = values.getOrNull(4)?.toInt()?.takeUnless { it == 0 },
            underrunCount = values.getOrNull(5)?.coerceAtLeast(0) ?: 0,
            overflowCount = values.getOrNull(6)?.coerceAtLeast(0) ?: 0,
        )
    }

    override fun setPlaybackAudible(enabled: Boolean): Boolean = nativeSetPlaybackAudible(enabled)

    override fun setMicrophoneCaptureEnabled(enabled: Boolean): Boolean =
        nativeSetMicrophoneEnabled(enabled)

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

    private external fun nativeCreateGraphicsListener(path: String): Int

    private fun awaitGuestGraphicsMode(runtimeDirectory: File): String? {
        val status = File(runtimeDirectory, "graphics.ready")
        val deadline = SystemClock.uptimeMillis() + GUEST_GRAPHICS_TIMEOUT_MS
        readGraphicsMode(status)?.let { return it }
        val changed = CountDownLatch(1)
        val observer = object : FileObserver(
            runtimeDirectory,
            FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO,
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path == status.name) changed.countDown()
            }
        }
        observer.startWatching()
        return try {
            // Close the observer-registration race before waiting for the completed guest status write.
            readGraphicsMode(status)?.let { return it }
            val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
            if (changed.await(remaining, TimeUnit.MILLISECONDS)) readGraphicsMode(status) else null
        } finally {
            observer.stopWatching()
        }
    }

    private fun readGraphicsMode(status: File): String? = runCatching {
        if (!Files.isRegularFile(status.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            status.length() > MAX_GRAPHICS_STATUS_BYTES
        ) {
            null
        } else {
            status.readText().trim().takeIf { it in setOf("venus", "virgl", "software") }
        }
    }.getOrNull()

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
        refreshRateHz: Float,
        rendererPreference: String,
    ): Int

    private external fun nativeStop(): Boolean

    private external fun nativeActiveProcessId(): Int

    private external fun nativeLastError(): String

    private external fun nativeAttachSurface(surface: Surface, width: Int, height: Int): Boolean

    private external fun nativeDetachSurface()

    private external fun nativeResizeDisplay(width: Int, height: Int): Boolean

    private external fun nativeSendPointer(x: Int, y: Int, buttons: Int): Boolean

    private external fun nativeSendKey(keysym: Int, pressed: Boolean): Boolean

    private external fun nativeSendText(keysyms: IntArray): Boolean

    private external fun nativeClipboardSnapshot(): IntArray

    private external fun nativeOfferClipboardText(text: ByteArray): Boolean

    private external fun nativeRequestClipboardText(): Boolean

    private external fun nativeTakeClipboardText(): ByteArray?

    private external fun nativeAudioSnapshot(): LongArray

    private external fun nativeSetPlaybackAudible(enabled: Boolean): Boolean

    private external fun nativeSetMicrophoneEnabled(enabled: Boolean): Boolean

    private external fun nativeDisplayConnected(): Boolean

    private companion object {
        const val MAX_CLIPBOARD_TEXT_BYTES = 1024 * 1024
        const val MAX_GRAPHICS_STATUS_BYTES = 32L
        const val GUEST_GRAPHICS_TIMEOUT_MS = 12_000L

        init {
            System.loadLibrary("deskforge_engine")
        }
    }
}
