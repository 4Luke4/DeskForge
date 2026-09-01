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

/** The renderer selected after a runtime capability check. */
sealed interface RendererMode {
    data class Accelerated(val backend: String) : RendererMode
    data class Software(val reason: String) : RendererMode
}

/** A complete, user-displayable capability snapshot. */
data class RuntimeCapabilities(
    val prootAvailable: Boolean,
    val vulkanAvailable: Boolean,
    val audioAvailable: Boolean,
    val rendererMode: RendererMode,
    val detail: String,
)

/** Pixel geometry supplied to both Xvnc and the Android native window. */
data class DesktopViewport(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
) {
    init {
        require(widthPx in 640..4096 && heightPx in 480..4096)
        require(widthPx.toLong() * heightPx <= 16_777_216L)
        require(densityDpi in 120..640)
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
}

/** Single authoritative lifecycle for a managed Linux session. */
sealed interface SessionState {
    data object Idle : SessionState
    data class Preparing(val progress: Float) : SessionState
    data object Starting : SessionState
    data class Running(val processId: Int, val rendererMode: RendererMode) : SessionState
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
