package com.deskforge.app.model

import androidx.annotation.StringRes

/** Identifies how an immutable distro payload reaches the installer. */
enum class InstallSource {
    PLAY_ASSET_DELIVERY,
    LOCAL_DOCUMENT,
}

/** Immutable metadata used by the UI and installer without embedding transport logic. */
data class DistroDescriptor(
    val id: String,
    @StringRes val titleResource: Int,
    @StringRes val subtitleResource: Int,
    val version: String,
    val architecture: String,
    val source: InstallSource,
)

/** User policy applied at the next session boundary. Forced policies never silently fall back. */
enum class RendererPreference { AUTO, VENUS, VIRGL, LLVMPIPE }

enum class GraphicsBackend { VENUS_ZINK, VIRGL, LLVMPIPE }

/** User policy applied only at a session boundary; Native never silently falls back to RFB. */
enum class PresentationPreference { NATIVE, RFB }

/** Ordinals are part of the JNI snapshot contract; append new values and update native together. */
enum class PresentationPath { NATIVE_HARDWARE_BUFFER, NATIVE_EGL_UPLOAD, RFB }

/** Ordinals are part of the JNI snapshot contract; append new values and update native together. */
enum class PresentationStatus { UNAVAILABLE, STARTING, READY, SURFACE_DETACHED, FAILED, STOPPED }

/** Capability-relative presentation evidence, independent from the guest renderer decision. */
data class PresentationSnapshot(
    val preference: PresentationPreference = PresentationPreference.NATIVE,
    val status: PresentationStatus = PresentationStatus.UNAVAILABLE,
    val path: PresentationPath = PresentationPath.NATIVE_EGL_UPLOAD,
    val detail: String = "Presentation runtime has not been started",
    val targetRefreshRateHz: Float = 0f,
    val activeRefreshRateHz: Float = 0f,
    val submittedFramesPerSecond: Float = 0f,
    val missedFrameBudgetCount: Long = 0,
    val p95FrameTimeMs: Float = 0f,
    val maximumFrameTimeMs: Float = 0f,
)

enum class GraphicsFallbackReason {
    RUNTIME_UNAVAILABLE,
    SERVICE_UNAVAILABLE,
    SELF_TEST_FAILED,
    SOFTWARE_HOST_RENDERER,
    STARTUP_TIMEOUT,
    TRANSPORT_LOST,
    GUEST_PROBE_FAILED,
    REQUIRED_VULKAN_EXTENSIONS_MISSING,
    FORCED_RENDERER_UNAVAILABLE,
    USER_SELECTED,
}

/** Renderer and presentation decisions are reported independently to avoid false acceleration claims. */
sealed interface RendererMode {
    data class Accelerated(
        val backend: GraphicsBackend,
        val hostRenderer: String,
    ) : RendererMode

    data class Software(
        val backend: GraphicsBackend = GraphicsBackend.LLVMPIPE,
        val reason: GraphicsFallbackReason,
        val detail: String,
    ) : RendererMode
}

enum class GraphicsTransportStatus { UNAVAILABLE, STARTING, READY, FALLBACK, FAILED, STOPPED }

/** Sanitized renderer telemetry; descriptors and protocol payloads never enter application state. */
data class GraphicsTransportSnapshot(
    val status: GraphicsTransportStatus,
    val rendererMode: RendererMode,
    val requestedRenderer: RendererPreference = RendererPreference.AUTO,
    val refreshRateHz: Float = 0f,
)

/** A complete, user-displayable capability snapshot. */
data class RuntimeCapabilities(
    val prootAvailable: Boolean,
    val guestGraphicsAvailable: Boolean,
    val audioAvailable: Boolean,
    val rendererMode: RendererMode,
    val presentation: PresentationSnapshot,
    val detail: String,
)

/** Pixel geometry plus requested and observed rates supplied to the presentation runtime. */
data class DesktopViewport(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val targetRefreshRateHz: Float = 60f,
    val activeRefreshRateHz: Float = 60f,
) {
    init {
        require(widthPx in 640..4096 && heightPx in 480..4096)
        require(widthPx.toLong() * heightPx <= 16_777_216L)
        require(densityDpi in 120..640)
        require(targetRefreshRateHz.isFinite() && targetRefreshRateHz in 30f..240f)
        require(activeRefreshRateHz.isFinite() && activeRefreshRateHz in 30f..240f)
    }
}

/** Stable user-facing failure categories; implementation details remain in native or workflow logs. */
enum class SessionFailure {
    WORKSPACE_UNAVAILABLE,
    WAITING_FOR_WIFI,
    INSTALL_FAILED,
    RUNTIME_UNAVAILABLE,
    SESSION_ALREADY_RUNNING,
    SESSION_START_FAILED,
    SESSION_STOP_FAILED,
    DISPLAY_DISCONNECTED,
    GRAPHICS_RUNTIME_LOST,
    RENDERER_UNAVAILABLE,
    DISPLAY_RUNTIME_LOST,
}

/** Single authoritative lifecycle for a managed Linux session. */
sealed interface SessionState {
    data object Idle : SessionState
    data class Preparing(val progress: Float) : SessionState
    data object Starting : SessionState
    data class Running(
        val processId: Int,
        val rendererMode: RendererMode,
        val presentationPath: PresentationPath,
    ) : SessionState
    data object Stopping : SessionState
    data class Failed(val reason: SessionFailure, val recoverable: Boolean) : SessionState
}

enum class ClipboardTransportStatus {
    UNSUPPORTED,
    IDLE,
    REMOTE_AVAILABLE,
    SENDING,
    RECEIVING,
    RECEIVED,
    FAILED,
}

enum class ClipboardFailure {
    NO_PLAIN_TEXT,
    TEXT_TOO_LARGE,
    INVALID_TEXT,
    TRANSFER_TIMEOUT,
    TRANSFER_FAILED,
    ANDROID_CLIPBOARD_FAILED,
}

data class ClipboardTransportSnapshot(
    val status: ClipboardTransportStatus,
    val remoteTextAvailable: Boolean,
    val failure: ClipboardFailure? = null,
)

enum class AudioPlaybackStatus {
    UNAVAILABLE,
    IDLE,
    WAITING_FOR_FOCUS,
    PLAYING,
    FAILED,
}

enum class AudioMicrophoneStatus {
    OFF,
    ACTIVE,
    FAILED,
}

enum class AudioFailure {
    TRANSPORT_UNAVAILABLE,
    PLAYBACK_OPEN_FAILED,
    PLAYBACK_DISCONNECTED,
    AUDIO_FOCUS_DENIED,
    MICROPHONE_PERMISSION_DENIED,
    MICROPHONE_PERMISSION_REVOKED,
    MICROPHONE_OPEN_FAILED,
    MICROPHONE_DISCONNECTED,
}

/** Sanitized audio telemetry; audio samples never cross into Kotlin application state. */
data class AudioTransportSnapshot(
    val playbackStatus: AudioPlaybackStatus,
    val microphoneStatus: AudioMicrophoneStatus,
    val failure: AudioFailure? = null,
    val outputDeviceId: Int? = null,
    val inputDeviceId: Int? = null,
    val underrunCount: Long = 0,
    val overflowCount: Long = 0,
)

data class SessionAudioState(
    val playbackStatus: AudioPlaybackStatus = AudioPlaybackStatus.UNAVAILABLE,
    val microphoneStatus: AudioMicrophoneStatus = AudioMicrophoneStatus.OFF,
    val microphoneConsent: Boolean = false,
    val failure: AudioFailure? = null,
    val outputDeviceId: Int? = null,
    val inputDeviceId: Int? = null,
    val underrunCount: Long = 0,
    val overflowCount: Long = 0,
)

/** User-visible clipboard state; clipboard contents deliberately remain outside Compose state. */
sealed interface SessionClipboardState {
    data object Unavailable : SessionClipboardState
    data class Idle(val remoteTextAvailable: Boolean) : SessionClipboardState
    data object Sending : SessionClipboardState
    data object Receiving : SessionClipboardState
    data class Ready(val generation: Long) : SessionClipboardState
    data class Failed(
        val reason: ClipboardFailure,
        val remoteTextAvailable: Boolean,
    ) : SessionClipboardState
}
